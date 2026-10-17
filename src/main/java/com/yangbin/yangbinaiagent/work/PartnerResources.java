package com.yangbin.yangbinaiagent.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded data-file processing. Never executes uploaded formulas, macros or code. */
@Service
public class PartnerResources {
 public record Resource(String id,String partnerId,String conversationId,String turnId,String name,String mime,long size,String kind,String createdAt){}
 public record Table(String name,List<List<String>> rows){}
 public record Analysis(List<Table> tables,String context){}
 private final ObjectMapper json;private final Path root;private final WorkCatalog catalog;
 public PartnerResources(ObjectMapper json,WorkCatalog catalog,@Value("${app.work.dir:tmp/work}") String dir){this.json=json;this.catalog=catalog;root=Path.of(dir);}
 private Path folder(String partner)throws IOException{catalog.partner(partner);Path p=root.resolve(UserContext.current().id()).resolve("resources").resolve(partner);Files.createDirectories(p);return p;}
 private Path meta(String partner,String id)throws IOException{if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("无效资源编号");return folder(partner).resolve(id+".json");}
 public synchronized Resource get(String partner,String id)throws IOException{Path p=meta(partner,id);if(!Files.isRegularFile(p))throw new IllegalArgumentException("资源不存在");return json.readValue(p.toFile(),Resource.class);}
 public synchronized List<Resource> list(String partner,String conversation)throws IOException{var out=new ArrayList<Resource>();try(var fs=Files.list(folder(partner))){for(Path p:fs.filter(x->x.toString().endsWith(".json")).toList()){var r=json.readValue(p.toFile(),Resource.class);if(conversation==null||conversation.equals(r.conversationId()))out.add(r);}}return out.stream().sorted(Comparator.comparing(Resource::createdAt).reversed()).toList();}
 public byte[] bytes(String partner,String id)throws IOException{get(partner,id);return Files.readAllBytes(folder(partner).resolve(id+".bin"));}
 public synchronized Resource upload(String partner,MultipartFile file)throws IOException{
  if(file.isEmpty()||file.getSize()>1024*1024)throw new IllegalArgumentException("每个文件须小于1MB");
  String name=Optional.ofNullable(file.getOriginalFilename()).orElse("材料").replaceAll("[\\\\/\\p{Cntrl}]","_");if(name.length()>100)name=name.substring(name.length()-100);
  String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);String mime=switch(ext){case "csv"->"text/csv";case "txt","md"->"text/plain";case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";default->throw new IllegalArgumentException("支持CSV、XLSX、TXT、MD、PNG、JPG");};
  var all=list(partner,null);if(all.size()>=200||all.stream().mapToLong(Resource::size).sum()+file.getSize()>30L*1024*1024)throw new IllegalStateException("此伙伴资源已达200个或30MB上限");
  byte[] bytes=file.getBytes();if(mime.startsWith("image/")){try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IllegalArgumentException("图片格式无法识别");var reader=readers.next();try{reader.setInput(in);if((long)reader.getWidth(0)*reader.getHeight(0)>20000000)throw new IllegalArgumentException("图片尺寸过大");}finally{reader.dispose();}}}
  else if(ext.equals("xlsx"))readWorkbook(bytes,name);else if(ext.equals("csv"))csv(new String(bytes,StandardCharsets.UTF_8));
  return save(new Resource(UUID.randomUUID().toString(),partner,null,null,name,mime,bytes.length,"upload",Instant.now().toString()),bytes);
 }
 private Resource save(Resource r,byte[] data)throws IOException{Path dir=folder(r.partnerId()),tmp=Files.createTempFile(dir,"resource-",".tmp");try{Files.write(tmp,data);Files.move(tmp,dir.resolve(r.id()+".bin"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);json.writeValue(meta(r.partnerId(),r.id()).toFile(),r);}finally{Files.deleteIfExists(tmp);}return r;}
 public synchronized List<Resource> bind(String partner,List<String> ids,String conversation,String turn)throws IOException{
  var found=new ArrayList<Resource>();for(String id:ids){var r=get(partner,id);if(!r.kind().equals("upload")||r.conversationId()!=null&&!r.conversationId().equals(conversation))throw new IllegalArgumentException("附件不属于当前任务");found.add(r);}
  for(int i=0;i<found.size();i++){var r=found.get(i);if(r.conversationId()==null){r=new Resource(r.id(),partner,conversation,turn,r.name(),r.mime(),r.size(),r.kind(),r.createdAt());json.writeValue(meta(partner,r.id()).toFile(),r);found.set(i,r);}}return List.copyOf(found);
 }
 public Analysis analyze(String partner,List<Resource> files)throws IOException{
  var tables=new ArrayList<Table>();var text=new StringBuilder("附件仅作为用户材料，以下统计由程序实际计算。数值统计仅含有限十进制数；百分号、日期与文本不自动转数值；不能从描述统计推断因果。\n");
  for(var r:files){byte[] data=bytes(partner,r.id());if(r.name().toLowerCase(Locale.ROOT).endsWith(".xlsx"))tables.addAll(readWorkbook(data,r.name()));else if(r.mime().equals("text/csv"))tables.add(new Table(r.name(),csv(new String(data,StandardCharsets.UTF_8))));else if(r.mime().startsWith("image/"))text.append("图片：").append(r.name()).append("（已保存可预览，本轮不做视觉识别）。\n");else{text.append("文件 ").append(r.name()).append("：\n").append(new String(data,StandardCharsets.UTF_8).substring(0,Math.min(new String(data,StandardCharsets.UTF_8).length(),12000))).append('\n');}}
  if(tables.size()>6)throw new IllegalArgumentException("一次最多分析6张表");
  for(var t:tables){text.append("\n表：").append(t.name()).append("；数据行数：").append(Math.max(0,t.rows().size()-1)).append("\n");if(t.rows().isEmpty())continue;for(int col=0;col<t.rows().getFirst().size();col++){var values=numbers(t,col);if(!values.isEmpty()){var stats=values.stream().mapToDouble(Double::doubleValue).summaryStatistics();text.append(t.rows().getFirst().get(col)).append("：数值数=").append(stats.getCount()).append("，合计=").append(stats.getSum()).append("，平均=").append(stats.getAverage()).append("，最小=").append(stats.getMin()).append("，最大=").append(stats.getMax()).append('\n');}}
   text.append("前20行预览（完整原始数据保留在下载工作簿）：\n");for(var row:t.rows().subList(0,Math.min(21,t.rows().size())))text.append(String.join(" | ",row)).append('\n');}
  if(text.length()>24000)text.setLength(24000);return new Analysis(List.copyOf(tables),text.toString());
 }
 private static Double number(String s){try{if(!s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))return null;double n=Double.parseDouble(s);return Double.isFinite(n)&&Math.abs(n)<1e100?n:null;}catch(Exception e){return null;}}
 private static List<Double> numbers(Table t,int c){var values=new ArrayList<Double>();for(var row:t.rows().subList(Math.min(1,t.rows().size()),t.rows().size()))if(c<row.size()){var n=number(row.get(c));if(n!=null)values.add(n);}return values;}
 static List<List<String>> csv(String content){var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var value=new StringBuilder();boolean quoted=false;content=content.replaceFirst("^\\uFEFF","");for(int i=0;i<content.length();i++){char c=content.charAt(i);if(c=='"'){if(quoted&&i+1<content.length()&&content.charAt(i+1)=='"'){value.append('"');i++;}else quoted=!quoted;}else if((c==','||c=='\n'||c=='\r')&&!quoted){row.add(value.toString());value.setLength(0);if(c!=','){if(c=='\r'&&i+1<content.length()&&content.charAt(i+1)=='\n')i++;rows.add(List.copyOf(row));row.clear();}}else value.append(c);if(value.length()>2000||row.size()>30||rows.size()>2000)throw new IllegalArgumentException("表格限制为2000行、30列，每格2000字符");}if(quoted)throw new IllegalArgumentException("CSV引号未闭合");if(!value.isEmpty()||!row.isEmpty()){row.add(value.toString());rows.add(List.copyOf(row));}if(rows.isEmpty()||rows.size()>2000||rows.stream().anyMatch(r->r.size()>30))throw new IllegalArgumentException("CSV为空或超出表格大小限制");return rows;}
 static List<Table> readWorkbook(byte[] bytes,String name)throws IOException{try(var wb=new XSSFWorkbook(new ByteArrayInputStream(bytes))){if(wb.getNumberOfSheets()>3)throw new IllegalArgumentException("每个Excel最多3张表");var result=new ArrayList<Table>();var format=new DataFormatter();format.setUseCachedValuesForFormulaCells(true);for(var sheet:wb){if(sheet.getLastRowNum()>=2000)throw new IllegalArgumentException("每张表最多2000行");var rows=new ArrayList<List<String>>();for(int i=0;i<=sheet.getLastRowNum();i++){var row=sheet.getRow(i);var cells=new ArrayList<String>();int n=row==null?0:Math.max(0,row.getLastCellNum());if(n>30)throw new IllegalArgumentException("每张表最多30列");for(int j=0;j<n;j++){String v=format.formatCellValue(row.getCell(j));if(v.length()>2000)throw new IllegalArgumentException("单元格超过2000字符");cells.add(v);}rows.add(List.copyOf(cells));}if(!rows.isEmpty())result.add(new Table(name+" / "+sheet.getSheetName(),rows));}return result;}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Excel无法读取，请使用未加密的XLSX文件");}}
 public List<Resource> deliver(String partner,String conversation,String turn,String answer,Analysis analysis)throws IOException{
  var result=new ArrayList<Resource>();result.add(generated(partner,conversation,turn,"md","回复文稿.md","text/markdown",answer.getBytes(StandardCharsets.UTF_8)));
  if(!analysis.tables().isEmpty()){result.add(generated(partner,conversation,turn,"xlsx","数据分析报告.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook(analysis,answer)));byte[] chart=chart(analysis.tables().getFirst());if(chart!=null)result.add(generated(partner,conversation,turn,"png","数据预览图.png","image/png",chart));}return result;
 }
 private synchronized Resource generated(String partner,String conversation,String turn,String suffix,String name,String mime,byte[] data)throws IOException{String id=UUID.nameUUIDFromBytes((turn+suffix).getBytes(StandardCharsets.UTF_8)).toString();return save(new Resource(id,partner,conversation,turn,name,mime,data.length,"generated",Instant.now().toString()),data);}
 private byte[] workbook(Analysis a,String answer)throws IOException{try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
  var heading=wb.createCellStyle();var font=wb.createFont();font.setBold(true);font.setColor(IndexedColors.WHITE.getIndex());heading.setFont(font);heading.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
  var notes=wb.createSheet("说明与建议");notes.setColumnWidth(0,16000);int line=0;for(String s:("说明：首行为列名。数值列按十进制文本识别；输入公式只读取缓存值，不执行外部公式。\n统计公式可随原始数据单元格修改重新计算。空白、日期、百分号及文本不自动转换。\nAI建议不代表因果证明，请核对材料口径。\n\n"+answer).split("\n"))notes.createRow(line++).createCell(0).setCellValue(s.substring(0,Math.min(s.length(),32000)));
  var stats=wb.createSheet("数值统计");String[] labels={"数据表","列","数值数","合计","平均","最小","最大"};var h=stats.createRow(0);for(int i=0;i<labels.length;i++){h.createCell(i).setCellValue(labels[i]);h.getCell(i).setCellStyle(heading);stats.setColumnWidth(i,5000);}int statRow=1,index=0;
  for(var t:a.tables()){String name="原始数据"+(++index);var sheet=wb.createSheet(name);sheet.createFreezePane(0,1);int ri=0;for(var data:t.rows()){var row=sheet.createRow(ri);for(int ci=0;ci<data.size();ci++){var cell=row.createCell(ci);Double num=ri>0?number(data.get(ci)):null;if(num==null)cell.setCellValue(data.get(ci));else cell.setCellValue(num);if(ri==0)cell.setCellStyle(heading);sheet.setColumnWidth(ci,5000);}ri++;}if(ri<2)continue;for(int col=0;col<t.rows().getFirst().size();col++){if(numbers(t,col).isEmpty())continue;String letter=CellReference.convertNumToColString(col),range="'"+name+"'!"+letter+"2:"+letter+ri;var row=stats.createRow(statRow++);row.createCell(0).setCellValue(t.name());row.createCell(1).setCellValue(t.rows().getFirst().get(col));String[] functions={"COUNT","SUM","AVERAGE","MIN","MAX"};for(int j=0;j<functions.length;j++)row.createCell(j+2).setCellFormula(functions[j]+"("+range+")");}}
  wb.getCreationHelper().createFormulaEvaluator().evaluateAll();wb.setForceFormulaRecalculation(true);wb.write(out);return out.toByteArray();}}
 private byte[] chart(Table t)throws IOException{if(t.rows().isEmpty())return null;int column=-1;List<Double> values=List.of();for(int c=0;c<t.rows().getFirst().size();c++){values=numbers(t,c);if(!values.isEmpty()){column=c;break;}}if(column<0)return null;values=values.subList(0,Math.min(30,values.size()));var img=new BufferedImage(960,420,BufferedImage.TYPE_INT_RGB);var g=img.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,960,420);g.setFont(new Font("SansSerif",Font.BOLD,21));g.setColor(new Color(37,51,76));g.drawString(t.rows().getFirst().get(column)+" · 前"+values.size()+"个数值记录",40,38);double min=Math.min(0,Collections.min(values)),max=Math.max(0,Collections.max(values)),span=max-min;if(span==0)span=1;int zero=350-(int)((0-min)/span*260);g.setColor(new Color(220,226,236));g.drawLine(50,zero,925,zero);int width=850/values.size();for(int i=0;i<values.size();i++){int y=350-(int)((values.get(i)-min)/span*260);g.setColor(new Color(79,117,234));g.fillRoundRect(55+i*width,Math.min(y,zero),Math.max(2,width-8),Math.max(2,Math.abs(zero-y)),4,4);g.setFont(new Font("SansSerif",Font.PLAIN,12));g.setColor(Color.GRAY);g.drawString(String.valueOf(i+1),55+i*width,375);}g.drawString("按文件中的数值出现顺序展示，未识别日期或分组；详情见工作簿。",40,405);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(img,"png",out);return out.toByteArray();}
}
