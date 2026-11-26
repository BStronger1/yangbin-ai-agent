package com.yangbin.yangbinaiagent.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yangbin.yangbinaiagent.models.ModelProfileService;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Private Work evidence. Only redeem produces model-bound data; reports stay in the owner's domain. */
@Service
public class WorkEvidenceService {
    public record ReportInput(String requestId, String partnerId, String resourceId, String idColumn,
                              String amountColumn, BigDecimal reportedTotal) {}
    public record Row(int row, String orderId, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amount) {}
    public record Report(String id, String sourceVersion, String resourceId, String partnerId, String filename,
                         String idColumn, String amountColumn, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal reportedTotal,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal actualTotal,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal difference, List<Row> rows, List<Integer> matchingRows, String createdAt) {}
    public record Claim(BigDecimal claimedTotal, BigDecimal claimedDifference, Integer claimedRow,
                        String claimedOrderId, BigDecimal claimedAmount) {}
    public record ReportSummary(String id, String resourceId, String partnerId, String filename, String createdAt) {}
    public record Verification(boolean verified, List<String> failedChecks, List<String> checked) {}
    public record Destination(String profileId, String provider, String baseUrl, String model, String version) {}
    public record TransferInput(String requestId, String reportId, String sourceVersion, List<Integer> rows,
                                String projectId, String modelProfileId, String destinationVersion, String purpose,
                                boolean includeTotals, boolean confirmExternal, boolean allowTests) {}
    public record Transfer(String id, String reportId, String sourceVersion, List<Integer> rows, String projectId,
                           String purpose, boolean includeTotals, Destination destination, String expiresAt, boolean revoked, boolean allowTests) {}
    private record ReportRecord(ReportInput input, String version, String filename, String createdAt) {}

    private final PartnerResources resources;
    private final ObjectMapper json;
    private final Path root;
    private final Clock clock;

    @Autowired
    public WorkEvidenceService(PartnerResources resources, ObjectMapper json, @Value("${app.work.dir:tmp/work}") String directory) {
        this(resources, json, Path.of(directory), Clock.systemUTC());
    }
    WorkEvidenceService(PartnerResources resources, ObjectMapper json, Path directory, Clock clock) {
        this.resources = resources;
        this.json = json;
        this.root = directory;
        this.clock = clock;
    }

    private Path folder(String kind) throws IOException {
        Path folder = root.resolve(UserContext.current().id()).resolve("work-evidence").resolve(kind);
        Files.createDirectories(folder);
        return folder;
    }
    private static String id(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("无效编号");
        return value;
    }
    private Path recordPath(String kind, String key) throws IOException { return folder(kind).resolve(id(key) + ".json"); }
    private <T> T read(Path path, Class<T> type) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("记录不存在或不可访问");
        return json.readValue(path.toFile(), type);
    }
    private static void write(Path path, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(path.getParent(), ".evidence-", ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void capacity(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            if (stream.filter(p -> p.getFileName().toString().endsWith(".json")).limit(200).count() >= 200)
                throw new IllegalStateException("此账号记录已达 200 条上限");
        }
    }
    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.scale() > 2 || value.scale() < -2 || value.precision() > 18)
            throw new IllegalArgumentException("金额须为最多两位小数的十进制数");
        return value.setScale(2);
    }
    public synchronized Report createReport(ReportInput input) throws IOException {
        if (input == null || input.idColumn() == null || input.amountColumn() == null || input.idColumn().isBlank()
                || input.amountColumn().isBlank() || input.idColumn().equals(input.amountColumn()))
            throw new IllegalArgumentException("请选择订单编号列和金额列");
        Path path = recordPath("reports", input.requestId());
        id(input.resourceId());
        input = new ReportInput(input.requestId(), input.partnerId(), input.resourceId(), input.idColumn(), input.amountColumn(), money(input.reportedTotal()));
        if (Files.exists(path)) {
            var old = read(path, ReportRecord.class);
            if (!input.equals(old.input())) throw new IllegalStateException("报告编号已用于其他内容");
            return report(input.requestId());
        }
        var resource = resources.get(input.partnerId(), input.resourceId());
        if (!"text/csv".equals(resource.mime()) || !"upload".equals(resource.kind())) throw new IllegalArgumentException("请选择上传的订单 CSV");
        byte[] data = resources.bytes(input.partnerId(), input.resourceId());
        if (data.length > 1024 * 1024) throw new IllegalArgumentException("订单文件超过 1 MB");
        var record = new ReportRecord(input, digest(data), resource.name(), clock.instant().toString());
        var result = calculate(record, data);
        capacity(path.getParent());
        Path snapshot = folder("sources").resolve(record.version() + ".csv");
        if (!Files.exists(snapshot)) write(snapshot, data);
        write(path, json.writeValueAsBytes(record));
        return result;
    }
    private static int column(List<String> headers, String name) {
        int i = headers.indexOf(name);
        if (i < 0 || i != headers.lastIndexOf(name)) throw new IllegalArgumentException("列名缺失或重复： " + name);
        return i;
    }
    private static Report calculate(ReportRecord record, byte[] data) {
        var in = record.input();
        var table = PartnerResources.csv(new String(data, StandardCharsets.UTF_8));
        int key = column(table.getFirst(), in.idColumn()), amount = column(table.getFirst(), in.amountColumn());
        if (table.size() < 2) throw new IllegalArgumentException("订单表缺少数据行");
        var rows = new ArrayList<Row>();
        var seen = new HashSet<String>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (int i = 1; i < table.size(); i++) {
            var values = table.get(i);
            if (values.size() <= Math.max(key, amount) || values.get(key).isBlank())
                throw new IllegalArgumentException("订单第 " + (i + 1) + " 行缺少编号或金额");
            String order = values.get(key), raw = values.get(amount).strip();
            if (!seen.add(order)) throw new IllegalArgumentException("订单编号重复，无法按一笔一行核对");
            if (!raw.matches("[+-]?[0-9]{1,16}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("订单第 " + (i + 1) + " 行金额无效");
            BigDecimal value = money(new BigDecimal(raw));
            rows.add(new Row(i + 1, order, value));
            total = total.add(value);
        }
        BigDecimal difference = total.subtract(in.reportedTotal());
        var matches = rows.stream().filter(r -> r.amount().compareTo(difference) == 0).map(Row::row).toList();
        return new Report(in.requestId(), record.version(), in.resourceId(), in.partnerId(), record.filename(),
                in.idColumn(), in.amountColumn(), in.reportedTotal(), total, difference, List.copyOf(rows), matches, record.createdAt());
    }
    public synchronized Report report(String key) throws IOException {
        return reopen(read(recordPath("reports", key), ReportRecord.class));
    }
    private Report reopen(ReportRecord record) throws IOException {
        return calculate(record, original(record));
    }
    private byte[] original(ReportRecord record) throws IOException {
        resources.get(record.input().partnerId(), record.input().resourceId());
        if (!record.version().matches("[a-f0-9]{64}")) throw new IllegalStateException("来源版本无效");
        Path source = folder("sources").resolve(record.version() + ".csv");
        if (!Files.isRegularFile(source)) throw new IllegalStateException("固定版本缺失");
        byte[] bytes = Files.readAllBytes(source);
        if (!digest(bytes).equals(record.version())) throw new IllegalStateException("固定版本内容摘要不符");
        return bytes;
    }
    public synchronized List<Report> reports(String partner, String resourceId) throws IOException {
        resources.get(partner, id(resourceId));
        var result = new ArrayList<Report>();
        try (var files = Files.list(folder("reports"))) {
            for (var path : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                var record = read(path, ReportRecord.class);
                if (partner.equals(record.input().partnerId()) && resourceId.equals(record.input().resourceId())) result.add(reopen(record));
            }
        }
        result.sort(Comparator.comparing(Report::createdAt).reversed());
        return result;
    }
    public synchronized List<ReportSummary> recentReports() throws IOException {
        var visible = new ArrayList<ReportSummary>();
        var sources = folder("sources");
        try (var files = Files.list(folder("reports"))) {
            for (var path : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                var record = read(path, ReportRecord.class);
                var in = record.input();
                try { resources.get(in.partnerId(),in.resourceId()); }
                catch (IllegalArgumentException removed) { continue; }
                if (!record.version().matches("[a-f0-9]{64}") || !Files.isRegularFile(sources.resolve(record.version()+".csv"))) continue;
                visible.add(new ReportSummary(in.requestId(),in.resourceId(),in.partnerId(),record.filename(),record.createdAt()));
            }
        }
        return visible.stream().sorted(Comparator.comparing(ReportSummary::createdAt).reversed().thenComparing(ReportSummary::id)).limit(5).toList();
    }
    public synchronized Verification verify(String reportId, Claim claim) throws IOException {
        if (claim == null) throw new IllegalArgumentException("缺少待核验结论");
        var r = report(reportId);
        var failures = new ArrayList<String>();
        var checked = new ArrayList<>(List.of("TOTAL","DIFFERENCE"));
        if (!equalMoney(claim.claimedTotal(), r.actualTotal())) failures.add("TOTAL");
        if (!equalMoney(claim.claimedDifference(), r.difference())) failures.add("DIFFERENCE");
        if (claim.claimedRow()!=null || claim.claimedOrderId()!=null || claim.claimedAmount()!=null) {
            checked.add("SOURCE_ROW");
            var row = r.rows().stream().filter(x -> Objects.equals(x.row(), claim.claimedRow())).findFirst();
            if (row.isEmpty() || !Objects.equals(row.get().orderId(), claim.claimedOrderId()) ||
                    !equalMoney(row.get().amount(), claim.claimedAmount()) || !r.matchingRows().contains(claim.claimedRow()))
                failures.add("SOURCE_ROW");
        }
        return new Verification(failures.isEmpty(), List.copyOf(failures), List.copyOf(checked));
    }
    private static boolean equalMoney(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) == 0; }
    public Destination destination(ModelProfileService.Snapshot model) throws IOException {
        if (model == null || model.profileId() == null || model.provider() == null || model.baseUrl() == null
                || model.model() == null || model.baseUrl().isBlank() || model.provider().isBlank() || model.model().isBlank())
            throw new IllegalStateException("模型目的地不完整");
        URI uri;
        try { uri = URI.create(model.baseUrl()); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("模型目的地不是有效 HTTP 地址"); }
        if (!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"")) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalStateException("模型目的地须为不含凭据、查询或片段的 HTTP 地址");
        String version = digest(json.writeValueAsBytes(List.of(model.profileId(), model.provider(), model.baseUrl(), model.model(), Objects.toString(model.api(), ""))));
        return new Destination(model.profileId(), model.provider(), model.baseUrl(), model.model(), version);
    }
    public synchronized Transfer createTransfer(TransferInput in, ModelProfileService.Snapshot model) throws IOException {
        if (in == null || !in.confirmExternal() || !Set.of("ANALYZE","PROPOSE_PATCH").contains(Objects.toString(in.purpose(),"")))
            throw new IllegalArgumentException("请确认用途、样本及向所选接收方发送材料");
        if (in.allowTests() && !"PROPOSE_PATCH".equals(in.purpose())) throw new IllegalArgumentException("仅候选修复可以授权沙箱测试");
        Path path = recordPath("transfers", in.requestId());
        id(in.projectId());
        var dest = destination(model);
        if (!dest.version().equals(in.destinationVersion())) throw new IllegalStateException("模型目的地已变化，请重新审阅");
        var report = report(in.reportId());
        if (!report.sourceVersion().equals(in.sourceVersion())) throw new IllegalStateException("来源版本不符");
        if (in.rows() == null || in.rows().isEmpty() || in.rows().size() > 32 ||
                in.rows().stream().anyMatch(n -> n == null || n < 2 || n > report.rows().size() + 1))
            throw new IllegalArgumentException("请选择最多 32 行有效样本");
        var selected = in.rows().stream().distinct().sorted().toList();
        if (Files.exists(path)) {
            var old = read(path, Transfer.class);
            if (!old.reportId().equals(in.reportId()) || !old.projectId().equals(in.projectId()) || !old.rows().equals(selected)
                    || old.includeTotals() != in.includeTotals() || old.allowTests() != in.allowTests() || !old.purpose().equals(in.purpose()) || !old.destination().equals(dest))
                throw new IllegalStateException("转交编号已用于其他内容");
            return old;
        }
        capacity(path.getParent());
        var value = new Transfer(in.requestId(), report.id(), report.sourceVersion(), selected, in.projectId(),
                in.purpose(), in.includeTotals(), dest, clock.instant().plus(Duration.ofHours(1)).toString(), false, in.allowTests());
        write(path, json.writeValueAsBytes(value));
        return value;
    }
    public synchronized Transfer inspect(String key) throws IOException { return read(recordPath("transfers", key), Transfer.class); }
    public synchronized List<Transfer> transfers(String reportId) throws IOException {
        read(recordPath("reports",reportId), ReportRecord.class);
        var result = new ArrayList<Transfer>();
        try (var files = Files.list(folder("transfers"))) {
            for (var path : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                var transfer = read(path, Transfer.class);
                if (reportId.equals(transfer.reportId())) result.add(transfer);
            }
        }
        result.sort(Comparator.comparing(Transfer::expiresAt).reversed());
        return result;
    }
    public synchronized Transfer revoke(String key) throws IOException {
        var old = inspect(key);
        if (old.revoked()) return old;
        var changed = new Transfer(old.id(), old.reportId(), old.sourceVersion(), old.rows(), old.projectId(),
                old.purpose(), old.includeTotals(), old.destination(), old.expiresAt(), true, old.allowTests());
        write(recordPath("transfers", key), json.writeValueAsBytes(changed));
        return changed;
    }
    public synchronized void authorize(String key, String projectId, ModelProfileService.Snapshot model, String action) throws IOException {
        var grant = checked(key, projectId, model, action);
        original(read(recordPath("reports", grant.reportId()),ReportRecord.class));
    }
    private Transfer checked(String key, String projectId, ModelProfileService.Snapshot model, String action) throws IOException {
        var grant = inspect(key);
        if (grant.revoked() || !Instant.parse(grant.expiresAt()).isAfter(clock.instant()) || !grant.projectId().equals(projectId)
                || !grant.destination().equals(destination(model))) throw new IllegalStateException("转交已失效，或与当前项目、模型目的地不符");
        boolean candidateAction = "PROPOSE_PATCH".equals(grant.purpose()) &&
                ("PROPOSE_PATCH".equals(action) || ("VERIFY".equals(action) && grant.allowTests()));
        if (!"READ".equals(action) && !candidateAction)
            throw new IllegalStateException("本次转交未授权此动作");
        var record = read(recordPath("reports",grant.reportId()),ReportRecord.class);
        if (!grant.sourceVersion().equals(record.version())) throw new IllegalStateException("固定版本不符");
        return grant;
    }
    public synchronized String redeem(String key, String projectId, ModelProfileService.Snapshot model, String action) throws IOException {
        var grant = checked(key, projectId, model, action);
        var report = report(grant.reportId());
        var selected = report.rows().stream().filter(row -> grant.rows().contains(row.row())).toList();
        var payload = new LinkedHashMap<String,Object>();
        payload.put("sourceVersion", grant.sourceVersion());
        payload.put("rows", selected);
        if (grant.includeTotals()) {
            payload.put("actualTotal", report.actualTotal().toPlainString());
            payload.put("reportedTotal", report.reportedTotal().toPlainString());
            payload.put("difference", report.difference().toPlainString());
        }
        return json.writeValueAsString(payload);
    }
}
