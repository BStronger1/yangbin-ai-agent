package com.yangbin.yangbinaiagent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bounded Chinese travel claim/evidence checks. No LLM, no network, no publication side effects.
 * SUPPORTED refers only to an extracted claim and the saved snapshot, never the entire answer.
 */
public final class SourceClaimReview {
    public static final String VERSION="travel-claims-v1";
    public static final String EVIDENCE_SCHEMA="planning-evidence-snapshot-v1";
    public record Claim(String field,int start,int end,String text,String category,String verdict,
                        String reason,List<String> evidencePointers) {}
    public record Report(String version,String checkedAt,String status,String inputHash,String inputHashFormat,
                         int unsupportedClaims,int contradictedClaims,int unassessedClauses,
                         List<Claim> claims,List<String> limitations) {}
    public record PlanReport(String planId,int planVersion,Report review) {}
    private record Field(String pointer,String text) {}
    private static final Pattern SEPARATOR=Pattern.compile("[。！？!?；;\\n，,]|但是|然而|不过|但");
    private static final Pattern WEATHER=Pattern.compile("天气|预报|晴|多云|阴天|雨|雪|气温|云层|降水");
    private static final Pattern CERTAINTY=Pattern.compile("确定|保证|肯定|必定|一定|绝不|绝对|不会|全天|整个下午|全程|整天|一滴");
    private static final Pattern CLOCK=Pattern.compile("(?:[0-9一二三四五六七八九十]+[点时分]|[0-9]{1,2}:[0-9]{2}|分钟|小时)");
    private static final Pattern FORECAST=Pattern.compile("(?:日预报|天气预报|预报)(?:显示|为|是)?(?:：|:)?(晴天|晴|多云|阴天|阴|小雨|中雨|大雨|暴雨|雷雨|雨|小雪|中雪|大雪|雪)");
    private static final Pattern PROBABILITY=Pattern.compile("降水概率(?:为|是|只有|约为)?\\s*([0-9]{1,3})%");
    private static final Pattern PRICE=Pattern.compile("票价|门票|价格|收费|人均|询价");
    private static final Pattern MONEY=Pattern.compile("[0-9一二三四五六七八九十百]+\\s*(?:元|块)|免费|确定|已确认|已核实|不用再询价|无需询价");
    private static final Pattern OPENING=Pattern.compile("营业|开放时间|开门|闭馆|闭店|预约");
    private static final Pattern OPENING_ASSERT=Pattern.compile("已确认|已核实|确定|当天|当日|营业到|营业至|开放至|无需预约|不用预约|免预约|不需预约");
    private static final Pattern ROUTE=Pattern.compile("步行|车程|路线|交通|通勤|骑行|公交|地铁|驾车");
    private static final Pattern DURATION=Pattern.compile("[0-9一二三四五六七八九十百]+\\s*(?:分钟|小时|公里|千米|米)");
    private static final Pattern EXHIBIT=Pattern.compile("馆藏|专题展|特展|展出|(?:举办|正在|观看|参观|有).{0,20}(?:展览|展陈|青铜器|油画展)");
    private static final Pattern REQUEST=Pattern.compile("^(?:如果|假如|假设|若|如遇|如有|假使)|是否|(?:请|需要|先|出发前|出行前|建议|需|应).{0,8}(?:确认|核实|询问|查询)|未核实|尚未|没有确认|未确认|无法确认|未获得|没有可用|未查询|不代表|不能(?:保证|确认|认定|断言|说)|不要(?:声称|认定|断言)|想看|希望看");
    private static final Pattern ESTIMATE=Pattern.compile("估算|估计|预算|预留|预计|大约|可能|或许|大概");
    private static final List<String> LIMITS=List.of(
        "这是中文出行快照的有限规则核验，不是通用语义模型；未检出问题不等于全文正确。",
        "逐条SUPPORTED仅指该条与保存来源对应；不证明当天实况、场所适用性或来源本身真实可靠。",
        "来源没有票价、具体展览、实际路线和当日预约核验能力；缺依据标为UNSUPPORTED，不推断现实中必然为假。",
        "复杂否定、引用、隐喻、跨句指代和未识别断言可能漏检或误报；未识别文本保留NOT_ASSESSABLE。",
        "只重算只读报告；不更新旧质量报告、方案、PDF或一次纠错记录，不重新联网。"
    );
    private SourceClaimReview() {}
    public static boolean supports(String language,String schema) {
        return "zh".equals(language)&&EVIDENCE_SCHEMA.equals(schema);
    }
    public static Report assess(PlannerService.Requirements requirements,PlannerService.Itinerary itinerary,
                                PlanningEvidence.Snapshot evidence) throws java.io.IOException {
        Map<String,Object> subject=new LinkedHashMap<>();
        subject.put("requirements",requirements);subject.put("itinerary",itinerary);subject.put("evidence",evidence);
        var fingerprint=ExecutionProvenance.unknown().capture("source-review",1,new ObjectMapper(),subject);
        List<Claim> claims=new ArrayList<>();
        for(Field field:fields(itinerary)) {
            if(field.text()==null||field.text().isBlank())continue;
            var matcher=SEPARATOR.matcher(field.text());int start=0;
            while(matcher.find()) {inspect(field,start,matcher.start(),requirements,evidence,claims);start=matcher.end();}
            inspect(field,start,field.text().length(),requirements,evidence,claims);
        }
        if(itinerary!=null&&itinerary.stops()!=null)for(int n=0;n<itinerary.stops().size();n++) {
            var stop=itinerary.stops().get(n);if(stop==null||stop.placeId()==null||stop.placeId().isBlank())continue;
            String id=stop.placeId();boolean known=evidence!=null&&evidence.places()!=null&&evidence.places().stream()
                .anyMatch(p->p!=null&&id.equals(p.id()));
            claims.add(new Claim("/itinerary/stops/"+n+"/placeId",0,id.length(),id,"PLACE_REFERENCE",
                evidence==null||evidence.places()==null?"NOT_ASSESSABLE":known?"SUPPORTED":"UNSUPPORTED",
                known?"引用编号存在于保存的候选中；不证明活动内容、营业或路线。":"保存快照没有可对应的地点编号。",
                evidence==null?List.of("/evidence"):List.of("/evidence/places")));
        }
        int unsupported=(int)claims.stream().filter(c->c.verdict().equals("UNSUPPORTED")).count();
        int contradicted=(int)claims.stream().filter(c->c.verdict().equals("CONTRADICTED")).count();
        int unknown=(int)claims.stream().filter(c->c.verdict().equals("NOT_ASSESSABLE")).count();
        return new Report(VERSION,Instant.now().toString(),
            unsupported+contradicted>0?"REVIEW_REQUIRED":claims.isEmpty()?"NOT_ASSESSABLE":"NO_UNSUPPORTED_CLAIMS_DETECTED",
            fingerprint.inputHash(),fingerprint.inputHashFormat(),unsupported,contradicted,unknown,List.copyOf(claims),LIMITS);
    }
    private static List<Field> fields(PlannerService.Itinerary i) {
        List<Field> f=new ArrayList<>();if(i==null)return f;
        f.add(new Field("/itinerary/title",i.title()));f.add(new Field("/itinerary/summary",i.summary()));
        if(i.stops()!=null)for(int n=0;n<i.stops().size();n++)if(i.stops().get(n)!=null) {
            f.add(new Field("/itinerary/stops/"+n+"/activity",i.stops().get(n).activity()));
            f.add(new Field("/itinerary/stops/"+n+"/note",i.stops().get(n).note()));
        }
        if(i.alternatives()!=null)for(int n=0;n<i.alternatives().size();n++)f.add(new Field("/itinerary/alternatives/"+n,i.alternatives().get(n)));
        if(i.cautions()!=null)for(int n=0;n<i.cautions().size();n++)f.add(new Field("/itinerary/cautions/"+n,i.cautions().get(n)));
        return f;
    }
    private static void add(List<Claim> claims,Field field,int start,int end,String category,String verdict,String reason,List<String> refs) {
        claims.add(new Claim(field.pointer(),start,end,field.text().substring(start,end),category,verdict,reason,refs));
    }
    private static void inspect(Field field,int from,int to,PlannerService.Requirements req,PlanningEvidence.Snapshot e,List<Claim> out) {
        while(from<to&&Character.isWhitespace(field.text().charAt(from)))from++;
        while(to>from&&Character.isWhitespace(field.text().charAt(to-1)))to--;
        if(from==to)return;
        String text=field.text().substring(from,to);
        boolean weather=WEATHER.matcher(text).find(),price=PRICE.matcher(text).find(),opening=OPENING.matcher(text).find(),
            route=ROUTE.matcher(text).find(),exhibit=EXHIBIT.matcher(text).find();
        List<String> refs=e==null?List.of("/evidence"):List.of("/evidence/notices");
        if(REQUEST.matcher(text).find()&&(weather||price||opening||route||exhibit)) {
            add(out,field,from,to,"UNCERTAINTY_OR_CONDITION","NOT_ASSERTION","该分句是条件、核实请求或明确未确认，不作为已发生的事实断言。",refs);return;
        }
        if(ESTIMATE.matcher(text).find()&&(price||opening||route||exhibit)) {
            add(out,field,from,to,"ESTIMATE_OR_POSSIBILITY","NOT_ASSESSABLE","属于估算或可能性表达，来源不足以确认其现实准确性。",refs);return;
        }
        boolean found=false;
        if(weather) {
            found=true;
            var w=e==null?null:e.weather();
            boolean usable=req!=null&&w!=null&&"available".equals(e.weatherStatus())&&Objects.equals(req.date(),w.date());
            List<String> wr=e==null?List.of("/evidence"):List.of("/requirements/date","/evidence/weather","/evidence/weatherStatus");
            if(CERTAINTY.matcher(text).find()||CLOCK.matcher(text).find()) {
                add(out,field,from,to,"WEATHER_PRECISION","UNSUPPORTED","日级预报不能推出确定的全时段实况、分钟级变化或精确天气窗口。",wr);
            } else {
                var forecast=FORECAST.matcher(text);var probability=PROBABILITY.matcher(text);
                if(forecast.find()) {
                    String value=forecast.group(1);
                    String description=w==null?null:w.description();
                    String verdict=!usable?"UNSUPPORTED":Objects.equals(value,description)?"SUPPORTED":"NOT_ASSESSABLE";
                    // Only mutually exclusive exact sky categories can be marked contradicted; mixed descriptions abstain.
                    if(usable&&Set.of("晴","晴天","多云","阴","阴天").contains(value)
                        &&Set.of("晴","晴天","多云","阴","阴天").contains(description==null?"":description)) {
                        verdict=value.replace("天","").equals(description.replace("天",""))?"SUPPORTED":"CONTRADICTED";
                    }
                    add(out,field,from,to,"DAILY_FORECAST",verdict,!usable?"缺少同日期可用预报，不能支持这条天气陈述。":
                        verdict.equals("SUPPORTED")?"描述与所选日期快照对应；只代表快照内容。":
                        verdict.equals("CONTRADICTED")?"文字所称日预报与同日期快照描述不一致。":"描述包含未覆盖的语义差异，需要人工复核。",wr);
                } else if(probability.find()) {
                    int stated=Integer.parseInt(probability.group(1));
                    String verdict=!usable||w.rainProbability()==null?"NOT_ASSESSABLE":stated==w.rainProbability()?"SUPPORTED":"CONTRADICTED";
                    add(out,field,from,to,"RAIN_PROBABILITY",verdict,"只核对所称日降水概率与快照数值，不推导是否一定降雨。",wr);
                } else add(out,field,from,to,"WEATHER_OTHER","NOT_ASSESSABLE","未能把该天气表达解析为已覆盖的日预报字段；不能自动判为受支持。",wr);
            }
        }
        if(price&&MONEY.matcher(text).find()) {
            found=true;add(out,field,from,to,"CURRENT_PRICE","UNSUPPORTED","保存的地点/天气快照没有当前实际票价；历史宣传单或确定语气不能补足依据。",refs);
        }
        if(opening&&OPENING_ASSERT.matcher(text).find()) {
            found=true;add(out,field,from,to,"OPENING_OR_BOOKING","UNSUPPORTED","地点记录或原始营业时间不证明当天营业、预约要求已核实。",refs);
        }
        if(route&&DURATION.matcher(text).find()) {
            found=true;add(out,field,from,to,"ROUTE_TIME_OR_DISTANCE","UNSUPPORTED","地点坐标不等于路线计算结果，不能支持精确通行时间或距离。",refs);
        }
        if(exhibit) {
            found=true;add(out,field,from,to,"SPECIFIC_EXHIBITION","UNSUPPORTED","候选名称和类别没有提供具体馆藏/展览信息；这里只判缺少依据，不断言现实不存在。",refs);
        }
        if(!found)add(out,field,from,to,"UNCLASSIFIED","NOT_ASSESSABLE","该分句未落入当前有限核验范围，未被判为正确。",List.of());
    }
}
