package app;

import com.sun.net.httpserver.*;
import java.io.*;
import java.awt.Desktop;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;

public class MarketLedger {
  static final int PORT = Integer.getInteger("marketledger.port", 8080);
  static final Path DATA = dataHome();
  static final Path STOCKS = DATA.resolve("stocks.tsv"), CALLS = DATA.resolve("calls.tsv"), NOTES = DATA.resolve("notes.tsv"), EVENTS = DATA.resolve("events.tsv"), SIGNALS = DATA.resolve("signals.tsv"), SETTINGS = DATA.resolve("settings.properties");
  static final Path BACKUPS = DATA.resolve("backups");
  static Path dataHome(){
    String override=System.getProperty("marketledger.dataDir","").trim();
    if(!override.isBlank()) return Paths.get(override).toAbsolutePath();
    String local=System.getenv("LOCALAPPDATA");
    if(local!=null && !local.isBlank()) return Paths.get(local,"MarketLedgerPro");
    String home=System.getProperty("user.home",".");
    return Paths.get(home,".marketledger-pro");
  }
  static final Object LOCK = new Object();
  static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  record Stock(String symbol,String name,double price,double prev,String updated) {}
  record Call(long id,String symbol,String direction,double baseline,double threshold,String due,String note,String status,double resolved,double move,String created,String resolvedAt) {}
  record Note(long id,String title,String body,String tag,String created) {}
  record Event(long id,String when,String type,String impact,String scope,String title,String created) {}
  record Signal(long id,String symbol,long candleTs,String captured,double price,String signal,int score,String phase,double rsi,double vwap,double trend,double volRatio,double atrPct,double spreadPct,String marketText,String eventText,double r15,double r30,double r60) {}

  public static void main(String[] args) throws Exception {
    Files.createDirectories(DATA); Files.createDirectories(BACKUPS); migrateLegacyData(); seed(); backupData();
    HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",PORT),0);
    server.createContext("/", MarketLedger::route);
    server.setExecutor(Executors.newCachedThreadPool()); server.start();
    System.out.println("Market Ledger running at http://localhost:"+PORT);
    System.out.println("Data folder: "+DATA.toAbsolutePath());
    System.out.println("Mobile: connect phone to the same Wi-Fi and open http://"+lanIp()+":"+PORT);
    try { if(Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI("http://localhost:"+PORT)); } catch(Throwable ignored){}
  }


  static void migrateLegacyData() throws IOException {
    Path legacy=Paths.get("data").toAbsolutePath().normalize();
    if(legacy.equals(DATA.toAbsolutePath().normalize()) || !Files.isDirectory(legacy)) return;
    for(String name: List.of("stocks.tsv","calls.tsv","notes.tsv","events.tsv","signals.tsv")){
      Path src=legacy.resolve(name), dst=DATA.resolve(name);
      if(Files.exists(src) && !Files.exists(dst)) Files.copy(src,dst);
    }
  }
  static void backupData() {
    try{
      String stamp=LocalDate.now().toString(); Path dir=BACKUPS.resolve(stamp); Files.createDirectories(dir);
      for(Path src: List.of(STOCKS,CALLS,NOTES,EVENTS,SIGNALS,SETTINGS)) if(Files.exists(src)) Files.copy(src,dir.resolve(src.getFileName()),StandardCopyOption.REPLACE_EXISTING);
      try(var ds=Files.list(BACKUPS)){var dirs=ds.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList(); for(int i=14;i<dirs.size();i++) deleteTree(dirs.get(i));}
    }catch(Exception e){System.err.println("Backup warning: "+e.getMessage());}
  }
  static void deleteTree(Path p)throws IOException{try(var w=Files.walk(p)){for(Path x:w.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(x);}}

  static void route(HttpExchange x) throws IOException {
    try {
      String p=x.getRequestURI().getPath(), m=x.getRequestMethod();
      if(p.equals("/") && m.equals("GET")) { bytes(x,200,"text/html; charset=utf-8",resource("/resources/index.html")); return; }
      if(p.equals("/manifest.webmanifest") && m.equals("GET")) { bytes(x,200,"application/manifest+json; charset=utf-8",resource("/resources/manifest.webmanifest")); return; }
      if(p.equals("/sw.js") && m.equals("GET")) { bytes(x,200,"application/javascript; charset=utf-8",resource("/resources/sw.js")); return; }
      if(p.equals("/icon-192.png") && m.equals("GET")) { bytes(x,200,"image/png",resource("/resources/icon-192.png")); return; }
      if(p.equals("/icon-512.png") && m.equals("GET")) { bytes(x,200,"image/png",resource("/resources/icon-512.png")); return; }
      if(p.equals("/apple-touch-icon.png") && m.equals("GET")) { bytes(x,200,"image/png",resource("/resources/apple-touch-icon.png")); return; }
      if(p.equals("/api/dashboard") && m.equals("GET")) { json(x,200,dashboard()); return; }
      if(p.equals("/api/signals") && m.equals("POST")) { recordSignal(x); return; }
      if(p.equals("/api/signals/performance") && m.equals("GET")) { json(x,200,signalPerformance()); return; }
      if(p.equals("/api/signals/calibration") && m.equals("GET")) { json(x,200,signalCalibration(x)); return; }
      if(p.equals("/api/stocks") && m.equals("POST")) { addStock(x); return; }
      if(p.startsWith("/api/stocks/") && p.endsWith("/price") && m.equals("POST")) { setPrice(x,p.split("/")[3]); return; }
      if(p.startsWith("/api/stocks/") && m.equals("DELETE")) { deleteStock(x,p.substring("/api/stocks/".length())); return; }
      if(p.equals("/api/calls") && m.equals("POST")) { addCall(x); return; }
      if(p.matches("/api/calls/\\d+/resolve") && m.equals("POST")) { resolveCall(x,Long.parseLong(p.split("/")[3])); return; }
      if(p.matches("/api/calls/\\d+/void") && m.equals("POST")) { voidCall(x,Long.parseLong(p.split("/")[3])); return; }
      if(p.equals("/api/notes") && m.equals("POST")) { addNote(x); return; }
      if(p.equals("/api/events") && m.equals("POST")) { addEvent(x); return; }
      if(p.equals("/api/context/schwab/sync") && m.equals("POST")) { syncSchwab(x); return; }
      if(p.equals("/api/settings/marketdata") && m.equals("GET")) { marketSettingsGet(x); return; }
      if(p.equals("/api/settings/marketdata") && m.equals("POST")) { marketSettingsSave(x); return; }
      if(p.startsWith("/api/microstructure/") && m.equals("GET")) { microstructure(x,p.substring("/api/microstructure/".length())); return; }
      if(p.matches("/api/notes/\\d+") && m.equals("DELETE")) { deleteNote(x,Long.parseLong(p.split("/")[3])); return; }
      if(p.equals("/api/quotes/refresh") && m.equals("POST")) { refreshQuotes(x); return; }
      if(p.startsWith("/api/market/") && m.equals("GET")) { marketData(x,p.substring("/api/market/".length())); return; }
      if(p.equals("/api/info") && m.equals("GET")) { json(x,200,"{\"port\":"+PORT+",\"lanIp\":"+q(lanIp())+"}"); return; }
      if(p.equals("/api/export") && m.equals("GET")) { exportCsv(x); return; }
      json(x,404,"{\"error\":\"Not found\"}");
    } catch(Exception e) { json(x,400,"{\"error\":"+q(e.getMessage()==null?"Request failed":e.getMessage())+"}"); }
  }

  static String dashboard() throws IOException {
    synchronized(LOCK){
      var ss=readStocks(); var cs=readCalls(); var ns=readNotes(); var es=readEvents();
      long hit=cs.stream().filter(c->c.status.equals("HIT")).count(), miss=cs.stream().filter(c->c.status.equals("MISS")).count(), open=cs.stream().filter(c->c.status.equals("OPEN")).count();
      StringBuilder b=new StringBuilder("{\"stocks\":[");
      for(int i=0;i<ss.size();i++){if(i>0)b.append(','); var s=ss.get(i); b.append("{\"symbol\":").append(q(s.symbol)).append(",\"name\":").append(q(s.name)).append(",\"price\":").append(s.price).append(",\"prev\":").append(s.prev).append(",\"updated\":").append(q(s.updated)).append('}');}
      b.append("],\"calls\":[");
      for(int i=0;i<cs.size();i++){if(i>0)b.append(',');var c=cs.get(i);b.append(callJson(c));}
      b.append("],\"notes\":[");
      for(int i=0;i<ns.size();i++){if(i>0)b.append(',');var n=ns.get(i);b.append("{\"id\":").append(n.id).append(",\"title\":").append(q(n.title)).append(",\"body\":").append(q(n.body)).append(",\"tag\":").append(q(n.tag)).append(",\"created\":").append(q(n.created)).append('}');}
      b.append("],\"events\":[");
      for(int i=0;i<es.size();i++){if(i>0)b.append(',');var e=es.get(i);b.append("{\"id\":"+e.id+",\"when\":"+q(e.when)+",\"type\":"+q(e.type)+",\"impact\":"+q(e.impact)+",\"scope\":"+q(e.scope)+",\"title\":"+q(e.title)+"}");}
      double acc=(hit+miss)==0?0:hit*100.0/(hit+miss);
      b.append("],\"stats\":{\"open\":").append(open).append(",\"hit\":").append(hit).append(",\"miss\":").append(miss).append(",\"accuracy\":").append(String.format(Locale.US,"%.1f",acc)).append("}}"); return b.toString();
    }
  }

  static void addStock(HttpExchange x)throws Exception{Map<String,String> f=form(x);String sym=req(f,"symbol").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.-]",""); if(sym.isBlank())throw new Exception("Invalid ticker"); double price=num(f.get("price")); synchronized(LOCK){var s=readStocks(); if(s.stream().anyMatch(a->a.symbol.equalsIgnoreCase(sym)))throw new Exception("Ticker already exists");s.add(new Stock(sym,f.getOrDefault("name",""),price,price,now()));writeStocks(s);}ok(x);}
  static void setPrice(HttpExchange x,String sym)throws Exception{double price=num(form(x).get("price"));if(price<=0)throw new Exception("Price must be positive"); synchronized(LOCK){var s=readStocks();boolean found=false;for(int i=0;i<s.size();i++)if(s.get(i).symbol.equalsIgnoreCase(sym)){var a=s.get(i);s.set(i,new Stock(a.symbol,a.name,price,a.price,now()));found=true;}if(!found)throw new Exception("Ticker not found");writeStocks(s);settleDue(sym,price);}ok(x);}
  static void deleteStock(HttpExchange x,String sym)throws Exception{synchronized(LOCK){var cs=readCalls();if(cs.stream().anyMatch(c->c.symbol.equalsIgnoreCase(sym)))throw new Exception("Delete or keep its call history first; tickers with calls are protected.");var s=readStocks();s.removeIf(a->a.symbol.equalsIgnoreCase(sym));writeStocks(s);}ok(x);}
  static void addCall(HttpExchange x)throws Exception{Map<String,String>f=form(x);String sym=req(f,"symbol").toUpperCase(Locale.ROOT),dir=req(f,"direction").toUpperCase(Locale.ROOT);if(!dir.equals("UP")&&!dir.equals("DOWN"))throw new Exception("Direction must be UP or DOWN");double base=num(req(f,"baseline")),thr=num0(f.get("threshold"));LocalDate.parse(req(f,"due"));synchronized(LOCK){if(readStocks().stream().noneMatch(s->s.symbol.equalsIgnoreCase(sym)))throw new Exception("Add ticker to watchlist first");var cs=readCalls();long id=cs.stream().mapToLong(Call::id).max().orElse(0)+1;cs.add(0,new Call(id,sym,dir,base,thr,f.get("due"),f.getOrDefault("note",""),"OPEN",0,0,now(),""));writeCalls(cs);}ok(x);}
  static void resolveCall(HttpExchange x,long id)throws Exception{double price=num(req(form(x),"price"));synchronized(LOCK){var cs=readCalls();boolean found=false;for(int i=0;i<cs.size();i++)if(cs.get(i).id==id){var c=cs.get(i);if(!c.status.equals("OPEN"))throw new Exception("Call already settled");cs.set(i,settled(c,price));found=true;}if(!found)throw new Exception("Call not found");writeCalls(cs);}ok(x);}
  static void voidCall(HttpExchange x,long id)throws Exception{synchronized(LOCK){var cs=readCalls();for(int i=0;i<cs.size();i++)if(cs.get(i).id==id){var c=cs.get(i);if(!c.status.equals("OPEN"))throw new Exception("Only open calls can be voided");cs.set(i,new Call(c.id,c.symbol,c.direction,c.baseline,c.threshold,c.due,c.note,"VOID",0,0,c.created,now()));}writeCalls(cs);}ok(x);}
  static void addNote(HttpExchange x)throws Exception{Map<String,String>f=form(x);synchronized(LOCK){var ns=readNotes();long id=ns.stream().mapToLong(Note::id).max().orElse(0)+1;ns.add(0,new Note(id,req(f,"title"),f.getOrDefault("body",""),f.getOrDefault("tag","RESEARCH"),now()));writeNotes(ns);}ok(x);}
  static void deleteNote(HttpExchange x,long id)throws Exception{synchronized(LOCK){var ns=readNotes();ns.removeIf(n->n.id==id);writeNotes(ns);}ok(x);}


  static void addEvent(HttpExchange x)throws Exception{Map<String,String>f=form(x);String when=req(f,"when"); if(!when.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"))throw new Exception("Use date/time as YYYY-MM-DD HH:MM ET"); synchronized(LOCK){var es=readEvents();long id=es.stream().mapToLong(Event::id).max().orElse(0)+1;es.add(new Event(id,when,req(f,"type"),req(f,"impact"),f.getOrDefault("scope","ALL").toUpperCase(Locale.ROOT),req(f,"title"),now()));es.sort(Comparator.comparing(Event::when));writeEvents(es);}ok(x);}
  static void syncSchwab(HttpExchange x)throws Exception{
    HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();
    String hub="https://schwabnetwork.com/markets/us-economy";
    HttpResponse<String> hr=client.send(HttpRequest.newBuilder(URI.create(hub)).timeout(java.time.Duration.ofSeconds(15)).header("User-Agent","Mozilla/5.0 MarketLedger/2.3").GET().build(),HttpResponse.BodyHandlers.ofString());
    if(hr.statusCode()!=200) throw new Exception("Schwab Network returned HTTP "+hr.statusCode());
    String html=hr.body();
    java.util.regex.Matcher lm=java.util.regex.Pattern.compile("href=[\\\"']([^\\\"']*week-ahead[^\\\"']*)[\\\"']",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
    String link=null; if(lm.find()) link=lm.group(1); if(link==null) throw new Exception("Could not discover the latest Schwab Week Ahead article");
    if(link.startsWith("/")) link="https://schwabnetwork.com"+link; else if(!link.startsWith("http")) link="https://schwabnetwork.com/"+link;
    HttpResponse<String> ar=client.send(HttpRequest.newBuilder(URI.create(link)).timeout(java.time.Duration.ofSeconds(15)).header("User-Agent","Mozilla/5.0 MarketLedger/2.3").GET().build(),HttpResponse.BodyHandlers.ofString());
    if(ar.statusCode()!=200) throw new Exception("Schwab article returned HTTP "+ar.statusCode());
    String text=htmlText(ar.body());
    var imported=parseSchwabCalendar(text);
    synchronized(LOCK){
      var es=readEvents(); long id=es.stream().mapToLong(Event::id).max().orElse(0)+1; int added=0;
      for(var e:imported){ boolean dup=es.stream().anyMatch(z->z.when.equals(e.when)&&z.title.equalsIgnoreCase(e.title)); if(!dup){es.add(new Event(id++,e.when,e.type,e.impact,e.scope,e.title+" [Schwab Week Ahead]",now()));added++;}}
      es.sort(Comparator.comparing(Event::when)); writeEvents(es);
      json(x,200,"{\"ok\":true,\"added\":"+added+",\"found\":"+imported.size()+",\"source\":"+q(link)+"}");
    }
  }
  static String htmlText(String h){return h.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("(?i)<br\\s*/?>","\\n").replaceAll("(?i)</(?:p|li|h[1-6]|div|tr)>","\\n").replaceAll("(?s)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replace("&#39;","'").replace("&quot;","\\\"").replaceAll("[ \\t]+"," ");}
  static List<Event> parseSchwabCalendar(String text){
    var out=new ArrayList<Event>(); int year=Year.now(ZoneId.of("America/New_York")).getValue(); LocalDate active=null;
    var datePat=java.util.regex.Pattern.compile("(?i)(Monday|Tuesday|Wednesday|Thursday|Friday)\\s+(\\d{1,2})/(\\d{1,2})");
    var timePat=java.util.regex.Pattern.compile("(?i)(\\d{1,2}):(\\d{2})\\s*(AM|PM)\\s*:?\\s*([^\\n]{3,120})");
    for(String raw:text.split("\\R")){String line=raw.trim(); if(line.isBlank())continue; var dm=datePat.matcher(line); if(dm.find()){try{active=LocalDate.of(year,Integer.parseInt(dm.group(2)),Integer.parseInt(dm.group(3)));}catch(Exception ignored){} continue;} if(active==null)continue; var tm=timePat.matcher(line); while(tm.find()){int h=Integer.parseInt(tm.group(1)),mi=Integer.parseInt(tm.group(2));String ap=tm.group(3).toUpperCase(Locale.ROOT);if(h==12)h=0;if(ap.equals("PM"))h+=12;String title=tm.group(4).replaceAll("\\s+"," ").trim();String type=title.toLowerCase(Locale.ROOT).contains("fed")?"FED":title.toLowerCase(Locale.ROOT).contains("auction")?"ECONOMIC":"ECONOMIC";String impact=(title.matches("(?i).*(CPI|PPI|Payroll|Employment|FOMC|Fed Chair|PMI|Jobless Claims|Durable Goods|Retail Sales|GDP).*"))?"HIGH":"MEDIUM";out.add(new Event(0,String.format(Locale.US,"%s %02d:%02d",active,h,mi),type,impact,"ALL",title,""));}}
    return out;
  }

  static List<Event> readEvents()throws IOException{var a=new ArrayList<Event>();if(!Files.exists(EVENTS))return a;for(String l:Files.readAllLines(EVENTS)){if(l.isBlank())continue;String[]p=l.split("\t",-1);if(p.length>=7)a.add(new Event(Long.parseLong(p[0]),un(p[1]),un(p[2]),un(p[3]),un(p[4]),un(p[5]),un(p[6])));}return a;}
  static void writeEvents(List<Event>a)throws IOException{var l=new ArrayList<String>();for(var e:a)l.add(e.id+"\t"+en(e.when)+"\t"+en(e.type)+"\t"+en(e.impact)+"\t"+en(e.scope)+"\t"+en(e.title)+"\t"+en(e.created));atomic(EVENTS,l);}


  static Properties marketSettings() throws IOException { Properties p=new Properties(); if(Files.exists(SETTINGS)) try(InputStream in=Files.newInputStream(SETTINGS)){p.load(in);} return p; }
  static String[] alpacaConnection(Properties p){String provider=p.getProperty("provider","YAHOO");String key=p.getProperty("alpaca.key",""),secret=p.getProperty("alpaca.secret","");if(!provider.equals("ALPACA"))return new String[]{"FALLBACK","Yahoo selected"};if(key.isBlank()||secret.isBlank())return new String[]{"MISSING","Alpaca credentials are incomplete"};try{HttpResponse<String> r=alpacaGet("https://data.alpaca.markets/v2/stocks/SPY/snapshot","iex");int c=r.statusCode();if(c==200)return new String[]{"CONNECTED","Authenticated Alpaca IEX snapshot available"};if(c==401||c==403)return new String[]{"AUTH_FAILED","Alpaca rejected the API credentials (HTTP "+c+")"};if(c==429)return new String[]{"RATE_LIMITED","Alpaca rate limit reached; Yahoo candle fallback remains active"};if(c==404||c==204)return new String[]{"NO_DATA","Alpaca authenticated but returned no snapshot data"};return new String[]{"FALLBACK","Alpaca returned HTTP "+c+"; Yahoo fallback remains active"};}catch(Exception e){return new String[]{"FALLBACK","Alpaca connection unavailable: "+(e.getMessage()==null?"request failed":e.getMessage())};}}
  static void marketSettingsGet(HttpExchange x)throws Exception{Properties p=marketSettings();String provider=p.getProperty("provider","YAHOO");boolean configured=!p.getProperty("alpaca.key","").isBlank()&&!p.getProperty("alpaca.secret","").isBlank();String[] c=alpacaConnection(p);json(x,200,"{\"provider\":"+q(provider)+",\"alpacaConfigured\":"+configured+",\"fallback\":\"YAHOO\",\"connection\":"+q(c[0])+",\"detail\":"+q(c[1])+"}");}
  static void marketSettingsSave(HttpExchange x)throws Exception{Map<String,String>f=form(x);Properties p=marketSettings();String key=f.getOrDefault("key","").trim(),secret=f.getOrDefault("secret","").trim();String provider=f.getOrDefault("provider",p.getProperty("provider","YAHOO")).toUpperCase(Locale.ROOT);if(!key.isBlank()&&!secret.isBlank())provider="ALPACA";if(!provider.equals("YAHOO")&&!provider.equals("ALPACA"))throw new Exception("Provider must be YAHOO or ALPACA");p.setProperty("provider",provider);if(!key.isBlank())p.setProperty("alpaca.key",key);if(!secret.isBlank())p.setProperty("alpaca.secret",secret);if("true".equalsIgnoreCase(f.getOrDefault("clear","false"))){p.remove("alpaca.key");p.remove("alpaca.secret");p.setProperty("provider","YAHOO");provider="YAHOO";}Files.createDirectories(DATA);Path tmp=SETTINGS.resolveSibling("settings.properties.tmp");try(OutputStream out=Files.newOutputStream(tmp)){p.store(out,"MarketLedger Pro local settings - keep private");}try{Files.move(tmp,SETTINGS,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,SETTINGS,StandardCopyOption.REPLACE_EXISTING);}p=marketSettings();String[] c=alpacaConnection(p);boolean configured=!p.getProperty("alpaca.key","").isBlank()&&!p.getProperty("alpaca.secret","").isBlank();json(x,200,"{\"ok\":true,\"provider\":"+q(p.getProperty("provider","YAHOO"))+",\"alpacaConfigured\":"+configured+",\"connection\":"+q(c[0])+",\"detail\":"+q(c[1])+"}");}
  static HttpResponse<String> alpacaGet(String url,String feed)throws Exception{Properties p=marketSettings();String key=p.getProperty("alpaca.key",""),secret=p.getProperty("alpaca.secret","");if(key.isBlank()||secret.isBlank())throw new Exception("Alpaca API key/secret not configured");String u=url+(url.contains("?")?"&":"?")+"feed="+URLEncoder.encode(feed,StandardCharsets.UTF_8);HttpClient c=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();return c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(12)).header("APCA-API-KEY-ID",key).header("APCA-API-SECRET-KEY",secret).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());}
  static String jsonNum(String body,String key){var m=java.util.regex.Pattern.compile("\\\""+key+"\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(body);return m.find()?m.group(1):"null";}
  static void microstructure(HttpExchange x,String raw)throws Exception{String sym=raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.-]","");if(sym.isBlank())throw new Exception("Invalid ticker");Properties p=marketSettings();String provider=p.getProperty("provider","YAHOO");if(!provider.equals("ALPACA")){json(x,200,"{\"provider\":\"YAHOO\",\"available\":false,\"reason\":\"Configure Alpaca for bid/ask and overnight liquidity\"}");return;}String phase=marketPhaseServer();String feed=phase.equals("OVERNIGHT")?"overnight":"iex";HttpResponse<String> r=alpacaGet("https://data.alpaca.markets/v2/stocks/"+URLEncoder.encode(sym,StandardCharsets.UTF_8)+"/snapshot",feed);if(r.statusCode()!=200){json(x,200,"{\"provider\":\"ALPACA\",\"available\":false,\"http\":"+r.statusCode()+"}");return;}String b=r.body(),bid=jsonNum(b,"bp"),ask=jsonNum(b,"ap"),bs=jsonNum(b,"bs"),as=jsonNum(b,"as"),last=jsonNum(b,"p"),barVol=jsonNum(b,"v");json(x,200,"{\"provider\":\"ALPACA\",\"available\":true,\"feed\":"+q(feed)+",\"bid\":"+bid+",\"ask\":"+ask+",\"bidSize\":"+bs+",\"askSize\":"+as+",\"last\":"+last+",\"minuteVolume\":"+barVol+"}");}
  static String marketPhaseServer(){ZonedDateTime z=ZonedDateTime.now(ZoneId.of("America/New_York"));int m=z.getHour()*60+z.getMinute();if(z.getDayOfWeek()==DayOfWeek.SATURDAY||z.getDayOfWeek()==DayOfWeek.SUNDAY)return "CLOSED";if(m<240||m>=1200)return "OVERNIGHT";if(m<570)return "PRE";if(m<960)return "REGULAR";return "POST";}
  static void marketData(HttpExchange x,String raw)throws Exception{
    String sym=raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.^=-]",""); if(sym.isBlank()) throw new Exception("Invalid ticker");
    // Yahoo remains the resilient candle fallback. Authenticated Alpaca is used in parallel for quote/spread/overnight microstructure.
    String u="https://query1.finance.yahoo.com/v8/finance/chart/"+URLEncoder.encode(sym,StandardCharsets.UTF_8)+"?range=5d&interval=5m&includePrePost=true&events=div%2Csplits";
    HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build(); HttpResponse<String> r=client.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(10)).header("User-Agent","Mozilla/5.0 MarketLedger/2.4").header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString()); if(r.statusCode()!=200) throw new Exception("Candle fallback provider returned HTTP "+r.statusCode()); bytes(x,200,"application/json; charset=utf-8",r.body().getBytes(StandardCharsets.UTF_8));
  }

  static String lanIp(){
    try{
      Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces();
      while(ns.hasMoreElements()){NetworkInterface n=ns.nextElement(); if(!n.isUp()||n.isLoopback()||n.isVirtual())continue; Enumeration<InetAddress> as=n.getInetAddresses(); while(as.hasMoreElements()){InetAddress a=as.nextElement(); if(a instanceof Inet4Address && !a.isLoopbackAddress())return a.getHostAddress();}}
    }catch(Exception ignored){} return "YOUR-PC-IP";
  }

  static void refreshQuotes(HttpExchange x)throws Exception{
    var ss=readStocks();int updated=0;
    HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();
    var out=new ArrayList<Stock>();
    for(var s:ss){try{String u="https://stooq.com/q/l/?s="+URLEncoder.encode(s.symbol.toLowerCase(Locale.ROOT)+".us",StandardCharsets.UTF_8)+"&f=sd2t2ohlcv&h&e=csv";var r=client.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(8)).header("User-Agent","MarketLedger/1.0").GET().build(),HttpResponse.BodyHandlers.ofString());String[] lines=r.body().split("\\R");if(lines.length>1){String[] c=lines[1].split(",");double p=Double.parseDouble(c[6]);out.add(new Stock(s.symbol,s.name,p,s.price,now()));settleDue(s.symbol,p);updated++;continue;}}catch(Exception ignored){}out.add(s);} synchronized(LOCK){writeStocks(out);} json(x,200,"{\"ok\":true,\"updated\":"+updated+",\"total\":"+ss.size()+"}");
  }
  static void settleDue(String sym,double price)throws IOException{synchronized(LOCK){var cs=readCalls();boolean ch=false;LocalDate today=LocalDate.now();for(int i=0;i<cs.size();i++){var c=cs.get(i);if(c.symbol.equalsIgnoreCase(sym)&&c.status.equals("OPEN")&&!LocalDate.parse(c.due).isAfter(today)){cs.set(i,settled(c,price));ch=true;}}if(ch)writeCalls(cs);}}
  static Call settled(Call c,double price){double mv=(price-c.baseline)/c.baseline*100.0;boolean hit=c.threshold>0?(c.direction.equals("UP")?mv>=c.threshold:mv<=-c.threshold):(c.direction.equals("UP")?price>c.baseline:price<c.baseline);return new Call(c.id,c.symbol,c.direction,c.baseline,c.threshold,c.due,c.note,hit?"HIT":"MISS",price,Math.round(mv*100.0)/100.0,c.created,now());}

  static void recordSignal(HttpExchange x)throws Exception{
    Map<String,String> f=form(x); String sym=req(f,"symbol").toUpperCase(Locale.ROOT); long ts=Long.parseLong(req(f,"candleTs")); double price=num(req(f,"price"));
    synchronized(LOCK){var a=readSignals(); boolean changed=false;
      for(int i=0;i<a.size();i++){var z=a.get(i); if(!z.symbol.equalsIgnoreCase(sym))continue; long age=ts-z.candleTs; double r15=z.r15,r30=z.r30,r60=z.r60;
        if(age>=15*60_000L && Double.isNaN(r15)){r15=(price/z.price-1)*100;changed=true;} if(age>=30*60_000L && Double.isNaN(r30)){r30=(price/z.price-1)*100;changed=true;} if(age>=60*60_000L && Double.isNaN(r60)){r60=(price/z.price-1)*100;changed=true;}
        if(r15!=z.r15||r30!=z.r30||r60!=z.r60)a.set(i,new Signal(z.id,z.symbol,z.candleTs,z.captured,z.price,z.signal,z.score,z.phase,z.rsi,z.vwap,z.trend,z.volRatio,z.atrPct,z.spreadPct,z.marketText,z.eventText,r15,r30,r60));
      }
      boolean exists=a.stream().anyMatch(z->z.symbol.equalsIgnoreCase(sym)&&z.candleTs==ts); if(!exists){long id=a.stream().mapToLong(Signal::id).max().orElse(0)+1;a.add(0,new Signal(id,sym,ts,now(),price,req(f,"signal"),(int)num(f.get("score")),f.getOrDefault("phase",""),num0(f.get("rsi")),num0(f.get("vwap")),num0(f.get("trend")),num0(f.get("volRatio")),num0(f.get("atrPct")),num0(f.get("spreadPct")),f.getOrDefault("marketText",""),f.getOrDefault("eventText",""),Double.NaN,Double.NaN,Double.NaN));changed=true;}
      if(changed)writeSignals(a); }
    ok(x);
  }
  static String signalPerformance()throws IOException{synchronized(LOCK){var a=readSignals();String[] labels={"STRONG_BUY","BUY","HOLD","WAIT","AVOID"};StringBuilder b=new StringBuilder("{\"total\":"+a.size()+",\"groups\":[");boolean first=true;for(String label:labels){var g=a.stream().filter(z->z.signal.equals(label)).toList();if(g.isEmpty())continue;if(!first)b.append(',');first=false;b.append("{\"signal\":").append(q(label)).append(",\"count\":").append(g.size());for(int h:new int[]{15,30,60}){double sum=0;int n=0,pos=0;for(var z:g){double r=h==15?z.r15:h==30?z.r30:z.r60;if(!Double.isNaN(r)){sum+=r;n++;if(r>0)pos++;}}b.append(",\"n").append(h).append("\":").append(n).append(",\"avg").append(h).append("\":").append(n==0?"null":String.format(Locale.US,"%.3f",sum/n)).append(",\"pos").append(h).append("\":").append(n==0?"null":String.format(Locale.US,"%.1f",pos*100.0/n));}b.append('}');}b.append("],\"recent\":[");for(int i=0;i<Math.min(30,a.size());i++){if(i>0)b.append(',');var z=a.get(i);b.append("{\"symbol\":").append(q(z.symbol)).append(",\"captured\":").append(q(z.captured)).append(",\"signal\":").append(q(z.signal)).append(",\"score\":").append(z.score).append(",\"price\":").append(z.price).append(",\"r15\":").append(jn(z.r15)).append(",\"r30\":").append(jn(z.r30)).append(",\"r60\":").append(jn(z.r60)).append('}');}b.append("]}");return b.toString();}}
  static String signalCalibration(HttpExchange x)throws IOException{
    Map<String,String> qv=query(x.getRequestURI().getRawQuery()); String dim=qv.getOrDefault("dimension","phase").toLowerCase(Locale.ROOT); int min=Math.max(1,(int)num0(qv.getOrDefault("min","20")));
    synchronized(LOCK){var a=readSignals(); Map<String,List<Signal>> groups=new TreeMap<>(); for(var z:a){String k=calKey(z,dim);groups.computeIfAbsent(k,_k->new ArrayList<>()).add(z);} StringBuilder b=new StringBuilder("{\"dimension\":"+q(dim)+",\"minimum\":"+min+",\"total\":"+a.size()+",\"groups\":[");boolean first=true;
      for(var e:groups.entrySet()){var g=e.getValue();if(!first)b.append(',');first=false;b.append("{\"name\":").append(q(e.getKey())).append(",\"count\":").append(g.size()).append(",\"qualified\":").append(g.size()>=min); appendOutcome(b,g,15);appendOutcome(b,g,30);appendOutcome(b,g,60);b.append('}');}return b.append("]}").toString();}
  }
  static void appendOutcome(StringBuilder b,List<Signal> g,int h){double sum=0,ss=0;int n=0,pos=0;for(var z:g){double r=h==15?z.r15:h==30?z.r30:z.r60;if(!Double.isNaN(r)){sum+=r;ss+=r*r;n++;if(r>0)pos++;}}double avg=n==0?Double.NaN:sum/n,sd=n<2?Double.NaN:Math.sqrt(Math.max(0,(ss-sum*sum/n)/(n-1)));b.append(",\"n").append(h).append("\":").append(n).append(",\"avg").append(h).append("\":").append(jn(avg)).append(",\"pos").append(h).append("\":").append(n==0?"null":String.format(Locale.US,"%.1f",pos*100.0/n)).append(",\"sd").append(h).append("\":").append(jn(sd));}
  static String calKey(Signal z,String dim){return switch(dim){case "symbol"->z.symbol;case "signal"->z.signal;case "score"->z.score<35?"0–34":z.score<48?"35–47":z.score<62?"48–61":z.score<75?"62–74":"75–100";case "spread"->z.spreadPct<=0?"Unavailable":z.spreadPct<.10?"<0.10%":z.spreadPct<.25?"0.10–0.24%":z.spreadPct<.50?"0.25–0.49%":"≥0.50%";case "event"->z.eventText==null||z.eventText.isBlank()?"No nearby event":"Nearby event";case "market"->{String t=z.marketText==null?"":z.marketText.toLowerCase(Locale.ROOT);yield t.contains("3/3")?"3/3 supportive":t.contains("2/3")?"2/3 supportive":t.contains("1/3")?"1/3 supportive":t.contains("0/3")?"0/3 supportive":"Unclassified";}default->z.phase==null||z.phase.isBlank()?"UNKNOWN":z.phase;};}
  static Map<String,String> query(String raw){Map<String,String> m=new HashMap<>();if(raw==null||raw.isBlank())return m;for(String p:raw.split("&")){String[]kv=p.split("=",2);m.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),kv.length>1?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"");}return m;}
  static String jn(double v){return Double.isNaN(v)?"null":String.format(Locale.US,"%.4f",v);}
  static List<Signal> readSignals()throws IOException{var a=new ArrayList<Signal>();if(!Files.exists(SIGNALS))return a;for(String l:Files.readAllLines(SIGNALS)){if(l.isBlank())continue;String[]p=l.split("\\t",-1);try{a.add(new Signal(Long.parseLong(p[0]),un(p[1]),Long.parseLong(p[2]),un(p[3]),d(p[4]),un(p[5]),Integer.parseInt(p[6]),un(p[7]),d(p[8]),d(p[9]),d(p[10]),d(p[11]),d(p[12]),d(p[13]),un(p[14]),un(p[15]),dn(p[16]),dn(p[17]),dn(p[18])));}catch(Exception ignored){}}return a;}
  static double dn(String s){return s==null||s.isBlank()?Double.NaN:d(s);}
  static void writeSignals(List<Signal>a)throws IOException{var l=new ArrayList<String>();for(var z:a)l.add(z.id+"\t"+en(z.symbol)+"\t"+z.candleTs+"\t"+en(z.captured)+"\t"+z.price+"\t"+en(z.signal)+"\t"+z.score+"\t"+en(z.phase)+"\t"+z.rsi+"\t"+z.vwap+"\t"+z.trend+"\t"+z.volRatio+"\t"+z.atrPct+"\t"+z.spreadPct+"\t"+en(z.marketText)+"\t"+en(z.eventText)+"\t"+(Double.isNaN(z.r15)?"":z.r15)+"\t"+(Double.isNaN(z.r30)?"":z.r30)+"\t"+(Double.isNaN(z.r60)?"":z.r60));atomic(SIGNALS,l);}

  static void exportCsv(HttpExchange x)throws IOException{StringBuilder b=new StringBuilder("id,symbol,direction,baseline,threshold_pct,due,status,resolved_price,move_pct,note,created,resolved_at\n");for(var c:readCalls())b.append(c.id).append(',').append(csv(c.symbol)).append(',').append(c.direction).append(',').append(c.baseline).append(',').append(c.threshold).append(',').append(c.due).append(',').append(c.status).append(',').append(c.resolved).append(',').append(c.move).append(',').append(csv(c.note)).append(',').append(csv(c.created)).append(',').append(csv(c.resolvedAt)).append('\n');byte[] z=b.toString().getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/csv; charset=utf-8");x.getResponseHeaders().set("Content-Disposition","attachment; filename=market-ledger.csv");x.sendResponseHeaders(200,z.length);x.getResponseBody().write(z);x.close();}

  static void seed()throws IOException{ synchronized(LOCK){if(!Files.exists(STOCKS)){writeStocks(new ArrayList<>(List.of(new Stock("NVDA","NVIDIA",0,0,""),new Stock("MU","Micron Technology",0,0,""),new Stock("COIN","Coinbase",0,0,""),new Stock("WDC","Western Digital",0,0,""),new Stock("STX","Seagate Technology",0,0,""),new Stock("BE","Bloom Energy",0,0,""),new Stock("ILMN","Illumina",0,0,""))));} if(!Files.exists(CALLS))writeCalls(new ArrayList<>()); if(!Files.exists(SIGNALS))writeSignals(new ArrayList<>()); if(!Files.exists(EVENTS))writeEvents(new ArrayList<>(List.of(new Event(1,"2026-09-21 09:30","REBALANCE","HIGH","BE","S&P 500 addition effective at Monday open",now()),new Event(2,"2026-09-21 09:30","REBALANCE","HIGH","ILMN","S&P 500 addition effective at Monday open",now()),new Event(3,"2026-09-23 09:45","ECONOMIC","HIGH","ALL","S&P Global PMI Index",now()),new Event(4,"2026-09-24 08:30","ECONOMIC","HIGH","ALL","Initial Jobless Claims",now()),new Event(5,"2026-09-25 08:30","ECONOMIC","HIGH","ALL","Durable Goods Orders",now())))); if(!Files.exists(NOTES))writeNotes(new ArrayList<>(List.of(new Note(1,"Options expiration / rebalance","Track the event, then record what actually happened. Treat directional claims as hypotheses, not guarantees.","EVENT",now()),new Note(2,"Sectors discussed","Memory / semiconductors, crypto-linked equities, storage, and index additions were recurring themes in the source conversation.","CONTEXT",now()))));}}
  static List<Stock> readStocks()throws IOException{var a=new ArrayList<Stock>();if(!Files.exists(STOCKS))return a;for(String l:Files.readAllLines(STOCKS)){if(l.isBlank())continue;String[]p=l.split("\\t",-1);a.add(new Stock(un(p[0]),un(p[1]),d(p[2]),d(p[3]),un(p[4])));}return a;}
  static void writeStocks(List<Stock>a)throws IOException{var l=new ArrayList<String>();for(var s:a)l.add(en(s.symbol)+"\t"+en(s.name)+"\t"+s.price+"\t"+s.prev+"\t"+en(s.updated));atomic(STOCKS,l);}
  static List<Call> readCalls()throws IOException{var a=new ArrayList<Call>();if(!Files.exists(CALLS))return a;for(String l:Files.readAllLines(CALLS)){if(l.isBlank())continue;String[]p=l.split("\\t",-1);a.add(new Call(Long.parseLong(p[0]),un(p[1]),un(p[2]),d(p[3]),d(p[4]),un(p[5]),un(p[6]),un(p[7]),d(p[8]),d(p[9]),un(p[10]),un(p[11])));}return a;}
  static void writeCalls(List<Call>a)throws IOException{var l=new ArrayList<String>();for(var c:a)l.add(c.id+"\t"+en(c.symbol)+"\t"+en(c.direction)+"\t"+c.baseline+"\t"+c.threshold+"\t"+en(c.due)+"\t"+en(c.note)+"\t"+en(c.status)+"\t"+c.resolved+"\t"+c.move+"\t"+en(c.created)+"\t"+en(c.resolvedAt));atomic(CALLS,l);}
  static List<Note> readNotes()throws IOException{var a=new ArrayList<Note>();if(!Files.exists(NOTES))return a;for(String l:Files.readAllLines(NOTES)){if(l.isBlank())continue;String[]p=l.split("\\t",-1);a.add(new Note(Long.parseLong(p[0]),un(p[1]),un(p[2]),un(p[3]),un(p[4])));}return a;}
  static void writeNotes(List<Note>a)throws IOException{var l=new ArrayList<String>();for(var n:a)l.add(n.id+"\t"+en(n.title)+"\t"+en(n.body)+"\t"+en(n.tag)+"\t"+en(n.created));atomic(NOTES,l);}
  static void atomic(Path p,List<String>l)throws IOException{Path t=p.resolveSibling(p.getFileName()+".tmp");Files.write(t,l,StandardCharsets.UTF_8);try{Files.move(t,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){Files.move(t,p,StandardCopyOption.REPLACE_EXISTING);}}

  static Map<String,String> form(HttpExchange x)throws IOException{String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);var m=new HashMap<String,String>();for(String z:body.split("&")){String[]p=z.split("=",2);if(p.length>0)m.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return m;}
  static String req(Map<String,String>m,String k)throws Exception{String v=m.get(k);if(v==null||v.isBlank())throw new Exception(k+" is required");return v.trim();}
  static double num(String s)throws Exception{try{return Double.parseDouble(s);}catch(Exception e){throw new Exception("Enter a valid number");}}
  static double num0(String s){try{return s==null||s.isBlank()?0:Double.parseDouble(s);}catch(Exception e){return 0;}}
  static double d(String s){try{return Double.parseDouble(s);}catch(Exception e){return 0;}}
  static String now(){return LocalDateTime.now().withNano(0).format(ISO);}
  static String en(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
  static String un(String s){try{return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}catch(Exception e){return "";}}
  static String q(String s){if(s==null)return"null";return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
  static String csv(String s){return "\""+(s==null?"":s).replace("\"","\"\"")+"\"";}
  static String callJson(Call c){return "{\"id\":"+c.id+",\"symbol\":"+q(c.symbol)+",\"direction\":"+q(c.direction)+",\"baseline\":"+c.baseline+",\"threshold\":"+c.threshold+",\"due\":"+q(c.due)+",\"note\":"+q(c.note)+",\"status\":"+q(c.status)+",\"resolved\":"+c.resolved+",\"move\":"+c.move+",\"created\":"+q(c.created)+",\"resolvedAt\":"+q(c.resolvedAt)+"}";}
  static byte[] resource(String n)throws IOException{try(InputStream in=MarketLedger.class.getResourceAsStream(n)){if(in==null)throw new FileNotFoundException(n);return in.readAllBytes();}}
  static void bytes(HttpExchange x,int code,String type,byte[] b)throws IOException{x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
  static void json(HttpExchange x,int code,String s)throws IOException{bytes(x,code,"application/json; charset=utf-8",s.getBytes(StandardCharsets.UTF_8));}
  static void ok(HttpExchange x)throws IOException{json(x,200,"{\"ok\":true}");}
}
