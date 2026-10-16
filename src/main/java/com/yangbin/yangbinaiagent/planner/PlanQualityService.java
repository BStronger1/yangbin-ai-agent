package com.yangbin.yangbinaiagent.planner;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic, conservative checks against a saved evidence snapshot. No model or network calls. */
public final class PlanQualityService {
    private PlanQualityService() {}
    public record Report(String ruleVersion,String checkedAt,String status,int matchedStops,int totalStops,
                         List<Check> checks,List<Finding> findings,List<String> limitations) {}
    public record Check(String id,String label,String status,String detail,String sourceUrl,Integer stopIndex) {}
    public record Finding(String code,String severity,String field,String message) {}
    private record TextField(String field,String text,Integer stopIndex) {}
    private static final Pattern HEALTH=Pattern.compile("疫情|防疫|防控|核酸|健康码");
    private static final Pattern VERIFIED=Pattern.compile("已(?:经)?(?:核实|确认|查证|验证)|经(?:过)?(?:核实|查证)|核实(?:结果|后确认)|确认为");
    private static final Pattern PRICE=Pattern.compile("票价|门票|价格|费用|收费|消费|免费");
    private static final Pattern OPENING=Pattern.compile("营业|开放时间|开放时段|正常开放|开门|闭馆|闭店");
    private static final Pattern TRAVEL=Pattern.compile("交通|车程|路程|通勤|步行|公交|地铁|打车|驾车|骑行|路线|耗时|分钟到达");
    private static final Pattern WEATHER=Pattern.compile("晴|多云|阴天|有雨|下雨|降雨|雨天|雨势|大雨|小雨|雷雨|暴雨|雪|气温|温度|降水概率");
    private static final Pattern WEATHER_ASSERTION=Pattern.compile(
            "(?:天气|预报|今天|当天|当日|明天|后天|周[一二三四五六日天]|[0-9]{4}年|[0-9]{1,2}月|[0-9]{4}-[0-9]{2}-[0-9]{2}).{0,40}(?:晴|多云|阴|雨|雪|气温|温度|降水)"
            +"|(?:全天|全程|整天|肯定|一定|必定|保证|确定).{0,15}(?:晴|雨|雪)"
            +"|(?:气温|温度).{0,12}[0-9]+(?:℃|度)");
    private static final Pattern WEATHER_PRECISION=Pattern.compile("(?:全天|整天|全程|必定|肯定|一定|保证|绝对|百分之百|100%).{0,15}(?:晴|雨|雪)|(?:雨|雪).{0,8}(?:一整天|全天)");
    private static final Pattern CONDITIONAL_WEATHER=Pattern.compile("如果|假如|假设|若|如遇|如有|万一|可能|视天气|雨天(?:备选|改|可|建议)|适合雨天|(?:下雨|降雨|雨天)(?:时|则|的话)|(?:天气|预报)(?:不可用|缺失)|未(?:获得|获取|查询到|核实|确认).{0,8}(?:天气|预报)");
    private static final List<String> LIMITATIONS=List.of(
            "本报告仅按 grounding-v1 做规则检查，不是全文事实核验，也不代表方案准确率或所有内容已经验证。",
            "地点匹配只检查 placeId 与候选完整名称在活动或备注中的字面对应；不能证明活动语义正确、没有夹杂其他地点，或实际位置与营业状态正确。",
            "天气只核对所选日期的已保存日预报快照；不验证全文天气描述、小时变化、全天持续天气或出行时实况。",
            "价格、当天营业状态、路线和交通耗时没有独立核验来源；预算检查只是估算费用的算术汇总。",
            "文本规则只覆盖部分明确断言，可能漏检或误报；条件、否定和复杂指代需要人工复核。旧快照不会在本检查中重新联网更新。"
    );

    public static Report assess(PlannerService.Requirements requirements,PlannerService.Itinerary itinerary,PlanningEvidence.Snapshot evidence) {
        List<Check> checks=new ArrayList<>();List<Finding> findings=new ArrayList<>();
        snapshotCheck(evidence,checks,findings);
        checks.add(new Check("practical-details","实际费用与营业交通","UNVERIFIED","当前来源未核实实际价格、当天营业状态、路线和交通耗时；请出行前另行确认。",null,null));
        List<PlannerService.Stop> stops=itinerary==null||itinerary.stops()==null?List.of():itinerary.stops();
        if(itinerary==null||itinerary.stops()==null||stops.isEmpty())
            findings.add(new Finding("MISSING_ITINERARY","warning","itinerary","方案缺少可检查的行程站点。"));
        Map<String,PlanningEvidence.Place> places=new HashMap<>();
        if(evidence!=null&&evidence.places()!=null)for(var place:evidence.places())
            if(place!=null&&place.id()!=null&&!place.id().isBlank())places.putIfAbsent(place.id(),place);
        int matched=0;
        for(int index=0;index<stops.size();index++) {
            var stop=stops.get(index);String field="itinerary.stops["+index+"]";
            if(stop==null) {
                checks.add(new Check("place-"+index,"地点引用与名称对应","UNVERIFIED","本站数据缺失。",null,index));
                continue;
            }
            if(stop.placeId()==null||stop.placeId().isBlank()) {
                checks.add(new Check("place-"+index,"地点引用与名称对应","UNVERIFIED","未引用具体地点，只能作为活动建议；不计入匹配站点。",null,index));
                continue;
            }
            var place=places.get(stop.placeId());
            if(place==null) {
                boolean missing=evidence==null||evidence.places()==null;
                checks.add(new Check("place-"+index,"地点引用与名称对应",missing?"UNVERIFIED":"CONFLICT",
                        missing?"缺少历史地点快照，无法核对引用。":"引用编号未出现在保存的候选地点中。",null,index));
                findings.add(new Finding(missing?"PLACE_EVIDENCE_MISSING":"PLACE_ID_NOT_FOUND",missing?"info":"warning",field+".placeId",
                        missing?"缺少可核对的地点来源快照。":"本站引用了未检索到的地点编号。"));
                continue;
            }
            String name=normalize(place.name());
            boolean nameMatches=!name.isBlank()&&(normalize(stop.activity()).contains(name)||normalize(stop.note()).contains(name));
            if(nameMatches) {
                matched++;
                checks.add(new Check("place-"+index,"地点引用与名称对应","SUPPORTED","引用编号与候选地点「"+place.name()+"」的完整名称能在本站文字中对应；未核实营业或价格。",safeUrl(place.url()),index));
            } else {
                checks.add(new Check("place-"+index,"地点引用与名称对应","CONFLICT","编号对应候选地点「"+place.name()+"」，但本站活动和备注未包含该完整名称。",safeUrl(place.url()),index));
                findings.add(new Finding("PLACE_NAME_MISMATCH","warning",field,"地点编号与本站文字未建立完整名称对应，请人工核对。"));
            }
        }
        boolean usableWeather=weatherCheck(requirements,evidence,checks,findings);
        budgetCheck(requirements,stops,checks,findings);
        for(TextField field:textFields(itinerary))inspectClaims(field,usableWeather,checks,findings);
        boolean warning=findings.stream().anyMatch(f->"warning".equals(f.severity()));
        return new Report("grounding-v1",Instant.now().toString(),warning?"REVIEW_REQUIRED":"CHECKED_WITH_LIMITATIONS",matched,stops.size(),
                List.copyOf(checks),List.copyOf(findings),LIMITATIONS);
    }

    private static void snapshotCheck(PlanningEvidence.Snapshot evidence,List<Check> checks,List<Finding> findings) {
        if(evidence==null) {
            checks.add(new Check("snapshot","来源快照时间","UNVERIFIED","旧方案没有保存来源快照，本次不推断来源内容。",null,null));
            findings.add(new Finding("SNAPSHOT_MISSING","info","evidence","缺少历史来源快照；未重新查询。"));return;
        }
        try {
            Instant fetched=Instant.parse(evidence.fetchedAt());long age=Duration.between(fetched,Instant.now()).toSeconds();
            if(age>24*60*60||age< -300) {
                checks.add(new Check("snapshot","来源快照时间","UNVERIFIED",age<0?"来源时间晚于当前时间，需核对。":"来源快照超过 24 小时，仅用于追溯当时查询。",null,null));
                findings.add(new Finding(age<0?"SNAPSHOT_TIME_INVALID":"SNAPSHOT_STALE","info","evidence.fetchedAt","本次不重新联网，来源时效性需要人工确认。"));
            } else checks.add(new Check("snapshot","来源快照时间","SUPPORTED","已记录来源查询时间；记录时间不代表数据内容必然最新。",null,null));
        } catch(RuntimeException malformed) {
            checks.add(new Check("snapshot","来源快照时间","UNVERIFIED","来源没有有效查询时间。",null,null));
            findings.add(new Finding("SNAPSHOT_TIME_INVALID","info","evidence.fetchedAt","无法判断快照时效性。"));
        }
    }

    private static boolean weatherCheck(PlannerService.Requirements requirements,PlanningEvidence.Snapshot evidence,List<Check> checks,List<Finding> findings) {
        var weather=evidence==null?null:evidence.weather();String label="所选日期预报快照";
        if(requirements==null||requirements.date()==null||weather==null||!"available".equals(evidence.weatherStatus())) {
            checks.add(new Check("weather",label,"UNVERIFIED","没有可用的所选日期天气依据；不能据此认定晴天、雨天或其他天气事实。",null,null));return false;
        }
        boolean sameDate=false;
        try {sameDate=LocalDate.parse(requirements.date()).equals(LocalDate.parse(weather.date()));}catch(RuntimeException ignored) {}
        if(!sameDate) {
            checks.add(new Check("weather",label,"CONFLICT","保存的天气日期与出行日期不匹配，不能作为本次出行天气依据。",safeUrl(weather.url()),null));
            findings.add(new Finding("WEATHER_DATE_MISMATCH","warning","evidence.weather.date","天气快照日期与所选出行日期不匹配。"));return false;
        }
        if(weather.description()==null||weather.description().isBlank()||!Double.isFinite(weather.minC())||!Double.isFinite(weather.maxC())||weather.minC()>weather.maxC()) {
            checks.add(new Check("weather",label,"UNVERIFIED","天气快照内容不完整，不能作为可用的天气描述依据。",safeUrl(weather.url()),null));return false;
        }
        checks.add(new Check("weather",label,"SUPPORTED","存在与所选日期对应的日预报快照；仅支持来源和日期对应，不验证方案中的全部天气陈述。",safeUrl(weather.url()),null));
        return true;
    }

    private static void budgetCheck(PlannerService.Requirements requirements,List<PlannerService.Stop> stops,List<Check> checks,List<Finding> findings) {
        long total=0;boolean invalid=false;
        for(int index=0;index<stops.size();index++) {
            var stop=stops.get(index);
            if(stop==null||stop.estimatedCost()<0) {
                invalid=true;findings.add(new Finding("INVALID_COST","warning","itinerary.stops["+index+"].estimatedCost","费用缺失或为负数，无法完成有效预算核对。"));
            } else total+=stop.estimatedCost();
        }
        if(requirements==null||requirements.budget()<1||stops.isEmpty()) {
            checks.add(new Check("budget","估算费用汇总","UNVERIFIED","缺少有效预算或站点，无法完成算术核对。",null,null));return;
        }
        boolean exceeded=total>requirements.budget();
        if(exceeded)findings.add(new Finding("BUDGET_EXCEEDED","warning","itinerary.stops","站点估算费用合计超过两人总预算。"));
        checks.add(new Check("budget","估算费用汇总",invalid||exceeded?"CONFLICT":"SUPPORTED",
                "有效站点费用合计 "+total+" 元；两人预算 "+requirements.budget()+" 元。只核对算术，不核实实际收费。",null,null));
    }

    private static List<TextField> textFields(PlannerService.Itinerary itinerary) {
        List<TextField> fields=new ArrayList<>();if(itinerary==null)return fields;
        fields.add(new TextField("itinerary.title",itinerary.title(),null));fields.add(new TextField("itinerary.summary",itinerary.summary(),null));
        if(itinerary.stops()!=null)for(int index=0;index<itinerary.stops().size();index++) {
            var stop=itinerary.stops().get(index);if(stop==null)continue;
            fields.add(new TextField("itinerary.stops["+index+"].activity",stop.activity(),index));
            fields.add(new TextField("itinerary.stops["+index+"].note",stop.note(),index));
        }
        if(itinerary.alternatives()!=null)for(int index=0;index<itinerary.alternatives().size();index++)fields.add(new TextField("itinerary.alternatives["+index+"]",itinerary.alternatives().get(index),null));
        if(itinerary.cautions()!=null)for(int index=0;index<itinerary.cautions().size();index++)fields.add(new TextField("itinerary.cautions["+index+"]",itinerary.cautions().get(index),null));
        return fields;
    }
    private static void inspectClaims(TextField field,boolean usableWeather,List<Check> checks,List<Finding> findings) {
        if(field.text()==null||field.text().isBlank())return;
        Set<String> emitted=new HashSet<>();
        for(String sentence:field.text().split("[。！？!?；;\\n]")) {
            String weatherSentence=sentence.strip();
            boolean conditional=CONDITIONAL_WEATHER.matcher(weatherSentence).find()
                    ||weatherSentence.matches(".*(?:不能|不可|不应|不要)(?:声称|认定|断言|说).*");
            if(!conditional&&WEATHER.matcher(weatherSentence).find()) {
                if(!usableWeather&&WEATHER_ASSERTION.matcher(weatherSentence).find())
                    claim("WEATHER_UNSUPPORTED_CLAIM","缺少匹配日期的天气依据，但文字作出了明确天气陈述。",field,checks,findings,emitted);
                if(usableWeather&&WEATHER_PRECISION.matcher(weatherSentence).find())
                    claim("WEATHER_RESOLUTION_UNSUPPORTED","日预报不能支持全天持续或确定性天气断言，请改为有边界的预报说明。",field,checks,findings,emitted);
            }
            // Separate clauses so a reminder to check one fact cannot mask a definite claim elsewhere.
            for(String clause:sentence.split("[，,]")) {
                String text=clause.strip();if(text.isEmpty())continue;
                if(HEALTH.matcher(text).find()&&!healthIsConditional(text))
                    claim("HEALTH_RESTRICTION_UNSUPPORTED","文字涉及当前疫情或防疫要求，但保存来源没有政策核验依据。",field,checks,findings,emitted);
                if(VERIFIED.matcher(text).find()&&!verificationIsConditional(text)) {
                    if(PRICE.matcher(text).find())claim("PRICE_VERIFICATION_UNSUPPORTED","保存来源不支持已核实票价或实际费用的声明。",field,checks,findings,emitted);
                    if(OPENING.matcher(text).find())claim("OPENING_VERIFICATION_UNSUPPORTED","地点记录不能证明当天营业或开放状态已经核实。",field,checks,findings,emitted);
                    if(TRAVEL.matcher(text).find())claim("TRAVEL_TIME_VERIFICATION_UNSUPPORTED","保存来源没有实际路线或交通耗时核验结果。",field,checks,findings,emitted);
                }
            }
        }
    }
    private static boolean healthIsConditional(String text) {
        // An actual pandemic assertion must not be excused just because the sentence also says “please check”.
        boolean period=text.contains("疫情期间")||text.matches(".*(?:当前|目前|正值|现在).{0,8}(?:有疫情|疫情期|疫情严重).*");
        if(period&&!text.matches("^(?:如果|假如|若|如遇).*"))return false;
        return text.matches(".*以.{0,12}(?:公告|通知|官方信息).{0,5}为准.*")
                ||text.matches(".*(?:是否|如有|如需|若有|若需|如果有|如果需要).{0,18}(?:疫情|防疫|防控|核酸|健康码).*" )
                ||text.matches(".*(?:查询|查阅|咨询|查看).{0,20}(?:疫情|防疫|防控|核酸|健康码).*" )
                ||text.matches(".*(?:请|建议|需|应|出行前|提前|出发前).{0,8}(?:确认|了解|核对).{0,20}(?:疫情|防疫|防控|核酸|健康码).*" )
                ||text.matches(".*(?:未核实|未确认|没有|缺少|暂无|无法确认).{0,12}(?:疫情|防疫|防控|核酸|健康码).*" )
                ||text.matches(".*(?:疫情|防疫|防控|核酸|健康码).{0,12}(?:未核实|未确认|没有依据|暂无依据|无法确认).*" );
    }
    private static boolean verificationIsConditional(String text) {
        return text.matches("^(?:如果|假如|假设|若|如已).*" )
                ||text.matches(".*(?:不能|不可|不应|不要)(?:声称|认定|断言|说).*" )
                ||text.matches(".*(?:未核实|尚未确认|未确认|未查证|尚未核实).*" )
                ||text.matches(".*已确认.{0,12}(?:为|是)(?:估算|估计|预算).*" );
    }
    private static void claim(String code,String message,TextField field,List<Check> checks,List<Finding> findings,Set<String> emitted) {
        if(!emitted.add(code))return;
        findings.add(new Finding(code,"warning",field.field(),message));
        checks.add(new Check("claim-"+checks.size(),"明确声明的来源边界","UNVERIFIED",message,null,field.stopIndex()));
    }
    private static String normalize(String value) {
        return value==null?"":Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+","");
    }
    private static String safeUrl(String value) {
        if(value==null||value.length()>2048)return null;
        try {
            URI uri=URI.create(value);
            if(!Set.of("http","https").contains(uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT))
                    ||uri.getHost()==null||uri.getHost().isBlank()||uri.getRawUserInfo()!=null)return null;
            if(uri.getRawQuery()!=null)for(String part:uri.getRawQuery().split("&")) {
                String name=URLDecoder.decode(part.split("=",2)[0],StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if(name.matches(".*(?:key|token|secret|password|signature|credential|authorization).*"))return null;
            }
            return uri.toString();
        } catch(RuntimeException invalid) {return null;}
    }
}
