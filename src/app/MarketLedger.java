package app;

import com.sun.net.httpserver.*;
import java.io.*;
import java.awt.Desktop;
import java.net.*;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import java.sql.*;

public class MarketLedger {
  static final int PORT = port();
  static final String ACCESS_PASSWORD = System.getenv().getOrDefault("MARKETLEDGER_PASSWORD", "").trim();
  static final String DATABASE_URL = System.getenv().getOrDefault("DATABASE_URL", "").trim();
  static volatile boolean DATABASE_READY = false;
  static final boolean CLOUD_MODE = System.getenv("RENDER")!=null || !System.getenv().getOrDefault("ALPACA_API_KEY","").isBlank();
  static final Set<String> SESSIONS = java.util.concurrent.ConcurrentHashMap.newKeySet();
  static int port(){
    String e=System.getenv("PORT");
    if(e!=null&&!e.isBlank()) try{return Integer.parseInt(e.trim());}catch(Exception ignored){}
    return Integer.getInteger("marketledger.port",8080);
  }
  static final Path DATA = dataHome();
  static final Path STOCKS = DATA.resolve("stocks.tsv"), CALLS = DATA.resolve("calls.tsv"), NOTES = DATA.resolve("notes.tsv"), EVENTS = DATA.resolve("events.tsv"), SIGNALS = DATA.resolve("signals.tsv"), SETTINGS = DATA.resolve("settings.properties");
  static final Path BACKUPS = DATA.resolve("backups");
  static Path dataHome(){
    String override=System.getProperty("marketledger.dataDir","").trim();
    if(override.isBlank()) override=System.getenv().getOrDefault("MARKETLEDGER_DATA_DIR","").trim();
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
    Files.createDirectories(DATA); Files.createDirectories(BACKUPS); initDatabase(); hydrateFromDatabase(); migrateLegacyData(); seed(); syncAllDataToDatabase(); backupData();
    HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",PORT),0);
    server.createContext("/", MarketLedger::route);
    server.setExecutor(Executors.newCachedThreadPool()); server.start();
    startOutcomeWorker();
    startCorporateCalendarWorker();
    System.out.println("Market Ledger running at http://localhost:"+PORT);
    System.out.println("Data folder: "+DATA.toAbsolutePath());
    if(System.getenv("RENDER")==null){
      System.out.println("Mobile: connect phone to the same Wi-Fi and open http://"+lanIp()+":"+PORT);
      try { if(Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI("http://localhost:"+PORT)); } catch(Throwable ignored){}
    } else System.out.println("Cloud mode: HTTPS is terminated by the hosting platform.");
  }


  static Connection db() throws Exception {
    if(DATABASE_URL.isBlank()) throw new SQLException("DATABASE_URL is not configured");
    URI u=URI.create(DATABASE_URL.replaceFirst("^postgresql://","postgres://"));
    String[] ui=(u.getUserInfo()==null?"":u.getUserInfo()).split(":",2);
    String user=ui.length>0?URLDecoder.decode(ui[0],StandardCharsets.UTF_8):"";
    String pass=ui.length>1?URLDecoder.decode(ui[1],StandardCharsets.UTF_8):"";
    String path=u.getPath()==null?"":u.getPath();
    String jdbc="jdbc:postgresql://"+u.getHost()+(u.getPort()>0?":"+u.getPort():"")+path;
    Properties props=new Properties(); props.setProperty("user",user); props.setProperty("password",pass);
    return DriverManager.getConnection(jdbc,props);
  }
  static void initDatabase() throws Exception {
    if(DATABASE_URL.isBlank()){System.out.println("Storage: local filesystem (not durable in free cloud containers)");return;}
    Class.forName("org.postgresql.Driver");
    try(Connection c=db(); Statement st=c.createStatement()){
      st.executeUpdate("CREATE TABLE IF NOT EXISTS marketledger_files (name VARCHAR(80) PRIMARY KEY, content BYTEA NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
      st.executeUpdate("CREATE TABLE IF NOT EXISTS watchlist (symbol VARCHAR(20) PRIMARY KEY, name TEXT NOT NULL DEFAULT '', price DOUBLE PRECISION, prev_price DOUBLE PRECISION, updated_at TEXT)");
      st.executeUpdate("CREATE TABLE IF NOT EXISTS signal_observations (id BIGINT PRIMARY KEY, symbol VARCHAR(20) NOT NULL, candle_ts BIGINT NOT NULL, captured_at TEXT, price DOUBLE PRECISION, final_status VARCHAR(30), technical_score INTEGER, session VARCHAR(30), rsi14 DOUBLE PRECISION, vwap DOUBLE PRECISION, momentum_5m DOUBLE PRECISION, relative_volume DOUBLE PRECISION, atr_pct DOUBLE PRECISION, spread_pct DOUBLE PRECISION, market_context TEXT, event_context TEXT, UNIQUE(symbol,candle_ts))");
      st.executeUpdate("CREATE TABLE IF NOT EXISTS signal_outcomes (signal_id BIGINT PRIMARY KEY REFERENCES signal_observations(id) ON DELETE CASCADE, return_15m DOUBLE PRECISION, return_30m DOUBLE PRECISION, return_60m DOUBLE PRECISION, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
      st.executeUpdate("ALTER TABLE signal_outcomes ADD COLUMN IF NOT EXISTS price_15m DOUBLE PRECISION");
      st.executeUpdate("ALTER TABLE signal_outcomes ADD COLUMN IF NOT EXISTS measured_15m_at TIMESTAMPTZ");
      st.executeUpdate("ALTER TABLE signal_outcomes ADD COLUMN IF NOT EXISTS price_30m DOUBLE PRECISION");
      st.executeUpdate("ALTER TABLE signal_outcomes ADD COLUMN IF NOT EXISTS measured_30m_at TIMESTAMPTZ");
      st.executeUpdate("ALTER TABLE signal_outcomes ADD COLUMN IF NOT EXISTS price_60m DOUBLE PRECISION");
      st.executeUpdate("ALTER TABLE signal_outcomes ADD COLUMN IF NOT EXISTS measured_60m_at TIMESTAMPTZ");
      st.executeUpdate("ALTER TABLE signal_outcomes ADD COLUMN IF NOT EXISTS max_gain_60m DOUBLE PRECISION");
      st.executeUpdate("ALTER TABLE signal_outcomes ADD COLUMN IF NOT EXISTS max_drawdown_60m DOUBLE PRECISION");
      st.executeUpdate("ALTER TABLE signal_observations ADD COLUMN IF NOT EXISTS captured_at_ts TIMESTAMPTZ");
      st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signal_obs_symbol_ts ON signal_observations(symbol,candle_ts)");
      st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signal_obs_status_session ON signal_observations(final_status,session)");
      st.executeUpdate("CREATE TABLE IF NOT EXISTS market_events (id BIGINT PRIMARY KEY, event_time TEXT, type VARCHAR(40), impact VARCHAR(20), scope TEXT, title TEXT, created_at TEXT)");
      st.executeUpdate("CREATE TABLE IF NOT EXISTS research_notes (id BIGINT PRIMARY KEY, title TEXT, body TEXT, tag VARCHAR(40), created_at TEXT)");
      st.executeUpdate("CREATE TABLE IF NOT EXISTS prediction_calls (id BIGINT PRIMARY KEY, symbol VARCHAR(20), direction VARCHAR(10), baseline DOUBLE PRECISION, threshold DOUBLE PRECISION, due_date TEXT, note TEXT, status VARCHAR(20), resolved_price DOUBLE PRECISION, move_pct DOUBLE PRECISION, created_at TEXT, resolved_at TEXT)");
    }
    DATABASE_READY=true; sanitizeCloudSettings(); System.out.println("Storage: PostgreSQL persistent database connected + native analytics schema ready");
  }
  static List<Path> dataFiles(){return List.of(STOCKS,CALLS,NOTES,EVENTS,SIGNALS,SETTINGS);}
  static void hydrateFromDatabase() throws Exception {
    if(!DATABASE_READY)return;
    try(Connection c=db(); PreparedStatement ps=c.prepareStatement("SELECT name, content FROM marketledger_files" ); ResultSet rs=ps.executeQuery()){
      while(rs.next()){
        String name=rs.getString(1); if(!Set.of("stocks.tsv","calls.tsv","notes.tsv","events.tsv","signals.tsv","settings.properties").contains(name))continue; if(CLOUD_MODE && name.equals("settings.properties")) continue;
        Files.write(DATA.resolve(name),rs.getBytes(2));
      }
    }
  }
  static void persistFile(Path p) throws IOException {
    if(!DATABASE_READY || !Files.exists(p))return; if(CLOUD_MODE && p.equals(SETTINGS)){sanitizeCloudSettings();return;}
    try(Connection c=db(); PreparedStatement ps=c.prepareStatement("INSERT INTO marketledger_files(name,content,updated_at) VALUES(?,?,NOW()) ON CONFLICT(name) DO UPDATE SET content=EXCLUDED.content, updated_at=NOW()")){
      ps.setString(1,p.getFileName().toString()); ps.setBytes(2,Files.readAllBytes(p)); ps.executeUpdate();
    }catch(Exception e){throw new IOException("Persistent database write failed: "+e.getMessage(),e);}
  }
  static void syncAllDataToDatabase() throws IOException { if(DATABASE_READY){ for(Path p:dataFiles()) if(Files.exists(p) && !(CLOUD_MODE && p.equals(SETTINGS))) persistFile(p); syncNativeTables(); } }
  static void sanitizeCloudSettings(){
    if(!DATABASE_READY || !CLOUD_MODE)return;
    try(Connection c=db(); PreparedStatement ps=c.prepareStatement("DELETE FROM marketledger_files WHERE name='settings.properties'")){ps.executeUpdate();}catch(Exception e){System.err.println("Credential cleanup warning: "+e.getMessage());}
    try{ if(Files.exists(SETTINGS)){Properties p=new Properties();try(InputStream in=Files.newInputStream(SETTINGS)){p.load(in);}p.remove("alpaca.key");p.remove("alpaca.secret");try(OutputStream out=Files.newOutputStream(SETTINGS)){p.store(out,"MarketLedger Pro non-secret settings");}} }catch(Exception e){System.err.println("Local settings cleanup warning: "+e.getMessage());}
  }
  static void syncNativeTables() throws IOException {
    if(!DATABASE_READY)return;
    try(Connection c=db()){
      c.setAutoCommit(false);
      try(Statement st=c.createStatement()){st.executeUpdate("DELETE FROM watchlist");st.executeUpdate("DELETE FROM market_events");st.executeUpdate("DELETE FROM research_notes");st.executeUpdate("DELETE FROM prediction_calls");}
      try(PreparedStatement ps=c.prepareStatement("INSERT INTO watchlist(symbol,name,price,prev_price,updated_at) VALUES(?,?,?,?,?)")){for(var z:readStocks()){ps.setString(1,z.symbol);ps.setString(2,z.name);ps.setDouble(3,z.price);ps.setDouble(4,z.prev);ps.setString(5,z.updated);ps.addBatch();}ps.executeBatch();}
      try(PreparedStatement so=c.prepareStatement("INSERT INTO signal_observations(id,symbol,candle_ts,captured_at,price,final_status,technical_score,session,rsi14,vwap,momentum_5m,relative_volume,atr_pct,spread_pct,market_context,event_context,captured_at_ts) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz) ON CONFLICT(id) DO UPDATE SET symbol=EXCLUDED.symbol,candle_ts=EXCLUDED.candle_ts,captured_at=EXCLUDED.captured_at,price=EXCLUDED.price,final_status=EXCLUDED.final_status,technical_score=EXCLUDED.technical_score,session=EXCLUDED.session,rsi14=EXCLUDED.rsi14,vwap=EXCLUDED.vwap,momentum_5m=EXCLUDED.momentum_5m,relative_volume=EXCLUDED.relative_volume,atr_pct=EXCLUDED.atr_pct,spread_pct=EXCLUDED.spread_pct,market_context=EXCLUDED.market_context,event_context=EXCLUDED.event_context,captured_at_ts=EXCLUDED.captured_at_ts"); PreparedStatement out=c.prepareStatement("INSERT INTO signal_outcomes(signal_id,return_15m,return_30m,return_60m) VALUES(?,?,?,?) ON CONFLICT(signal_id) DO UPDATE SET return_15m=COALESCE(signal_outcomes.return_15m,EXCLUDED.return_15m),return_30m=COALESCE(signal_outcomes.return_30m,EXCLUDED.return_30m),return_60m=COALESCE(signal_outcomes.return_60m,EXCLUDED.return_60m)")){for(var z:readSignals()){so.setLong(1,z.id);so.setString(2,z.symbol);so.setLong(3,z.candleTs);so.setString(4,z.captured);so.setDouble(5,z.price);so.setString(6,z.signal);so.setInt(7,z.score);so.setString(8,z.phase);so.setDouble(9,z.rsi);so.setDouble(10,z.vwap);so.setDouble(11,z.trend);so.setDouble(12,z.volRatio);so.setDouble(13,z.atrPct);so.setDouble(14,z.spreadPct);so.setString(15,z.marketText);so.setString(16,z.eventText);so.setString(17,z.captured==null||z.captured.isBlank()?null:z.captured+"Z");so.addBatch();out.setLong(1,z.id);if(Double.isNaN(z.r15))out.setNull(2,Types.DOUBLE);else out.setDouble(2,z.r15);if(Double.isNaN(z.r30))out.setNull(3,Types.DOUBLE);else out.setDouble(3,z.r30);if(Double.isNaN(z.r60))out.setNull(4,Types.DOUBLE);else out.setDouble(4,z.r60);out.addBatch();}so.executeBatch();out.executeBatch();}
      try(PreparedStatement ps=c.prepareStatement("INSERT INTO market_events(id,event_time,type,impact,scope,title,created_at) VALUES(?,?,?,?,?,?,?)")){for(var z:readEvents()){ps.setLong(1,z.id);ps.setString(2,z.when);ps.setString(3,z.type);ps.setString(4,z.impact);ps.setString(5,z.scope);ps.setString(6,z.title);ps.setString(7,z.created);ps.addBatch();}ps.executeBatch();}
      try(PreparedStatement ps=c.prepareStatement("INSERT INTO research_notes(id,title,body,tag,created_at) VALUES(?,?,?,?,?)")){for(var z:readNotes()){ps.setLong(1,z.id);ps.setString(2,z.title);ps.setString(3,z.body);ps.setString(4,z.tag);ps.setString(5,z.created);ps.addBatch();}ps.executeBatch();}
      try(PreparedStatement ps=c.prepareStatement("INSERT INTO prediction_calls(id,symbol,direction,baseline,threshold,due_date,note,status,resolved_price,move_pct,created_at,resolved_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")){for(var z:readCalls()){ps.setLong(1,z.id);ps.setString(2,z.symbol);ps.setString(3,z.direction);ps.setDouble(4,z.baseline);ps.setDouble(5,z.threshold);ps.setString(6,z.due);ps.setString(7,z.note);ps.setString(8,z.status);ps.setDouble(9,z.resolved);ps.setDouble(10,z.move);ps.setString(11,z.created);ps.setString(12,z.resolvedAt);ps.addBatch();}ps.executeBatch();}
      c.commit();
    }catch(Exception e){throw new IOException("Native PostgreSQL sync failed: "+e.getMessage(),e);}
  }


  record PricePoint(long ts,double close) {}
  static void startOutcomeWorker(){
    if(!DATABASE_READY)return;
    var scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"marketledger-outcomes");t.setDaemon(true);return t;});
    scheduler.scheduleWithFixedDelay(()->{try{measurePendingOutcomes();}catch(Throwable e){System.err.println("Outcome worker warning: "+e.getMessage());}},20,300,TimeUnit.SECONDS);
    System.out.println("Outcome engine: scheduled every 5 minutes (historical candle backfill enabled)");
  }
  static void measurePendingOutcomes() throws Exception {
    if(!DATABASE_READY)return;
    record Pending(long id,String symbol,long ts,double price,String session){}
    var pending=new ArrayList<Pending>();
    try(Connection c=db(); PreparedStatement ps=c.prepareStatement("SELECT o.id,o.symbol,o.candle_ts,o.price,o.session FROM signal_observations o JOIN signal_outcomes x ON x.signal_id=o.id WHERE x.return_15m IS NULL OR x.return_30m IS NULL OR x.return_60m IS NULL OR x.max_gain_60m IS NULL ORDER BY o.candle_ts DESC LIMIT 250"); ResultSet rs=ps.executeQuery()){
      while(rs.next())pending.add(new Pending(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getDouble(4),rs.getString(5)));
    }
    Map<String,List<PricePoint>> cache=new HashMap<>(); int updated=0;
    for(var p:pending){
      List<PricePoint> pts=cache.computeIfAbsent(p.symbol(),k->{try{return historicalPoints(k);}catch(Exception e){return List.of();}}); if(pts.isEmpty())continue;
      Double[] px=new Double[3]; Instant[] at=new Instant[3]; int[] mins={15,30,60};
      for(int i=0;i<3;i++){long target=p.ts()+mins[i]*60_000L; PricePoint q=nearestPoint(pts,target,p.session()); if(q!=null){px[i]=q.close();at[i]=Instant.ofEpochMilli(q.ts());}}
      double maxGain=Double.NaN,maxDraw=Double.NaN;
      for(var q:pts) if(q.ts()>p.ts()&&q.ts()<=p.ts()+60*60_000L&&sameSession(p.session(),q.ts())){double r=(q.close()/p.price()-1)*100;maxGain=Double.isNaN(maxGain)?r:Math.max(maxGain,r);maxDraw=Double.isNaN(maxDraw)?r:Math.min(maxDraw,r);}
      if(px[0]==null&&px[1]==null&&px[2]==null&&Double.isNaN(maxGain))continue;
      try(Connection c=db(); PreparedStatement u=c.prepareStatement("UPDATE signal_outcomes SET price_15m=COALESCE(price_15m,?),return_15m=COALESCE(return_15m,?),measured_15m_at=COALESCE(measured_15m_at,?),price_30m=COALESCE(price_30m,?),return_30m=COALESCE(return_30m,?),measured_30m_at=COALESCE(measured_30m_at,?),price_60m=COALESCE(price_60m,?),return_60m=COALESCE(return_60m,?),measured_60m_at=COALESCE(measured_60m_at,?),max_gain_60m=COALESCE(max_gain_60m,?),max_drawdown_60m=COALESCE(max_drawdown_60m,?),updated_at=NOW() WHERE signal_id=?")){
        int n=1; for(int i=0;i<3;i++){if(px[i]==null){u.setNull(n++,Types.DOUBLE);u.setNull(n++,Types.DOUBLE);u.setNull(n++,Types.TIMESTAMP_WITH_TIMEZONE);}else{u.setDouble(n++,px[i]);u.setDouble(n++,(px[i]/p.price()-1)*100);u.setObject(n++,at[i]);}} if(Double.isNaN(maxGain))u.setNull(n++,Types.DOUBLE);else u.setDouble(n++,maxGain); if(Double.isNaN(maxDraw))u.setNull(n++,Types.DOUBLE);else u.setDouble(n++,maxDraw);u.setLong(n,p.id());updated+=u.executeUpdate();}
    }
    if(updated>0)System.out.println("Outcome engine: updated "+updated+" signal outcome rows");
  }
  static PricePoint nearestPoint(List<PricePoint> pts,long target,String session){PricePoint best=null;long delta=Long.MAX_VALUE;for(var q:pts){long d=q.ts()-target;if(d<0||d>8*60_000L||!sameSession(session,q.ts()))continue;if(d<delta){best=q;delta=d;}}return best;}
  static boolean sameSession(String expected,long epochMs){String s=sessionForEpoch(epochMs); if(expected==null)return true; expected=expected.toUpperCase(Locale.ROOT); if(expected.equals("CLOSED"))return false; if(expected.equals("DISCOVERY")||expected.equals("CONFIRM")||expected.equals("AFTERNOON")||expected.equals("CLOSE")||expected.equals("REGULAR"))return s.equals("REGULAR"); return expected.equals(s);}
  static String sessionForEpoch(long ms){ZonedDateTime z=Instant.ofEpochMilli(ms).atZone(ZoneId.of("America/New_York"));int m=z.getHour()*60+z.getMinute();DayOfWeek d=z.getDayOfWeek();if(d==DayOfWeek.SATURDAY)return"CLOSED";if(d==DayOfWeek.SUNDAY)return m>=1200?"OVERNIGHT":"CLOSED";if(d==DayOfWeek.FRIDAY&&m>=1200)return"CLOSED";if(m<240||m>=1200)return"OVERNIGHT";if(m<570)return"PRE";if(m<960)return"REGULAR";return"POST";}
  static List<PricePoint> historicalPoints(String sym)throws Exception{
    String u="https://query1.finance.yahoo.com/v8/finance/chart/"+URLEncoder.encode(sym,StandardCharsets.UTF_8)+"?range=5d&interval=5m&includePrePost=true&events=div%2Csplits";
    HttpClient c=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(12)).header("User-Agent","Mozilla/5.0 MarketLedger/2.6").GET().build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()!=200)return List.of();String b=r.body();
    var tm=java.util.regex.Pattern.compile("\\\"timestamp\\\"\\s*:\\s*\\[([^]]*)\\]").matcher(b);var cm=java.util.regex.Pattern.compile("\\\"close\\\"\\s*:\\s*\\[([^]]*)\\]").matcher(b);if(!tm.find()||!cm.find())return List.of();String[] ts=tm.group(1).split(","),cs=cm.group(1).split(",");var out=new ArrayList<PricePoint>();for(int i=0;i<Math.min(ts.length,cs.length);i++){try{String cv=cs[i].trim();if(cv.equals("null"))continue;out.add(new PricePoint(Long.parseLong(ts[i].trim())*1000L,Double.parseDouble(cv)));}catch(Exception ignored){}}return out;
  }


  record EarningsHit(String symbol,long epoch,boolean estimated,String source) {}
  record EarningsSyncResult(int checked,int found,int changed,int failed,List<String> failures) {}
  record IpoHit(String symbol,String name,LocalDate date,String priceRange,String source) {}
  record ProviderDiag(String provider,int http,String contentType,String header,int rows,int parsed,int matches,String note) {}
  static volatile ProviderDiag LAST_EARNINGS_DIAG=new ProviderDiag("none",0,"","",0,0,0,"not synced");
  static volatile ProviderDiag LAST_IPO_DIAG=new ProviderDiag("none",0,"","",0,0,0,"not synced");
  record CorporateSyncResult(EarningsSyncResult earnings,int ipoFound,int ipoChanged,int ipoFailed,List<String> ipoFailures) {}

  static void startCorporateCalendarWorker(){
    var scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"marketledger-corporate-calendar");t.setDaemon(true);return t;});
    scheduler.scheduleWithFixedDelay(()->{
      try{
        CorporateSyncResult r=syncCorporateCalendar();
        System.out.println("Corporate calendar: earnings found="+r.earnings.found+" IPOs found="+r.ipoFound+
          " earnings failures="+r.earnings.failed+" IPO failures="+r.ipoFailed);
      }catch(Throwable e){System.err.println("Corporate calendar warning: "+e.getMessage());}
    },45,21600,TimeUnit.SECONDS);
    System.out.println("Corporate calendar: scheduled every 6 hours");
  }

  static String diagJson(ProviderDiag d){
    return "{\"provider\":\""+esc(d.provider)+"\",\"http\":"+d.http+",\"contentType\":\""+esc(d.contentType)+"\",\"header\":\""+esc(d.header)+"\",\"rows\":"+d.rows+",\"parsed\":"+d.parsed+",\"matches\":"+d.matches+",\"note\":\""+esc(d.note)+"\"}";
  }
  static void corporateDiagnosticsEndpoint(HttpExchange x)throws Exception{
    json(x,200,"{\"earnings\":"+diagJson(LAST_EARNINGS_DIAG)+",\"ipos\":"+diagJson(LAST_IPO_DIAG)+"}");
  }

  static void syncCorporateEndpoint(HttpExchange x)throws Exception{
    CorporateSyncResult r=syncCorporateCalendar();
    StringBuilder ef=new StringBuilder("[");
    for(int i=0;i<Math.min(5,r.earnings.failures.size());i++){if(i>0)ef.append(",");ef.append("\"").append(esc(r.earnings.failures.get(i))).append("\"");}
    ef.append("]");
    StringBuilder ipf=new StringBuilder("[");
    for(int i=0;i<Math.min(5,r.ipoFailures.size());i++){if(i>0)ipf.append(",");ipf.append("\"").append(esc(r.ipoFailures.get(i))).append("\"");}
    ipf.append("]");
    boolean av=System.getenv("ALPHA_VANTAGE_API_KEY")!=null&&!System.getenv("ALPHA_VANTAGE_API_KEY").isBlank();
    json(x,200,"{\"ok\":true,\"alphaVantageConfigured\":"+av+
      ",\"earnings\":{\"checked\":"+r.earnings.checked+",\"found\":"+r.earnings.found+",\"updated\":"+r.earnings.changed+
      ",\"failed\":"+r.earnings.failed+",\"failures\":"+ef+"}"+
      ",\"ipos\":{\"found\":"+r.ipoFound+",\"updated\":"+r.ipoChanged+",\"failed\":"+r.ipoFailed+",\"failures\":"+ipf+"}}");
  }

  static CorporateSyncResult syncCorporateCalendar() throws Exception{
    EarningsSyncResult er=syncUpcomingEarnings();
    int ipoChanged=0; var ipoFailures=new ArrayList<String>(); var ipos=new ArrayList<IpoHit>();
    boolean ipoPrimarySucceeded=false;
    try{ipos.addAll(fetchNasdaqIpos());ipoPrimarySucceeded=true;}
    catch(Exception e){ipoFailures.add("Nasdaq IPO: "+e.getMessage());}
    String key=System.getenv("ALPHA_VANTAGE_API_KEY");
    if(!ipoPrimarySucceeded && key!=null&&!key.isBlank()){
      try{ipos.addAll(fetchAlphaVantageIpos(key));}
      catch(Exception e){ipoFailures.add("Alpha Vantage IPO fallback: "+e.getMessage());}
    }

    synchronized(LOCK){
      var es=dedupeEvents(readEvents());
      long next=es.stream().mapToLong(Event::id).max().orElse(0)+1;
      long nowMs=System.currentTimeMillis();
      for(var h:ipos){
        long eventMs=h.date.atTime(9,30).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        if(eventMs<nowMs-24L*60*60*1000||eventMs>nowMs+95L*24*60*60*1000)continue;
        String scope=(h.symbol==null||h.symbol.isBlank())?"ALL":h.symbol;
        String when=h.date+" 09:30";
        String pr=(h.priceRange==null||h.priceRange.isBlank())?"":" • Price "+h.priceRange;
        String title="Upcoming IPO • "+h.name+pr+" • auto-synced • "+h.source;
        Event old=es.stream().filter(e->e.type.equalsIgnoreCase("IPO")&&e.scope.equalsIgnoreCase(scope)&&e.title.contains("auto-synced")).findFirst().orElse(null);
        if(old!=null){
          if(!old.when.equals(when)||!old.title.equals(title)){
            int idx=es.indexOf(old);es.set(idx,new Event(old.id,when,"IPO","MEDIUM",scope,title,old.created));ipoChanged++;
          }
        }else{es.add(new Event(next++,when,"IPO","MEDIUM",scope,title,now()));ipoChanged++;}
      }
      es=dedupeEvents(es); es.sort(Comparator.comparing(Event::when)); writeEvents(es);
    }
    return new CorporateSyncResult(er,ipos.size(),ipoChanged,ipoFailures.size(),ipoFailures);
  }

  static List<IpoHit> fetchAlphaVantageIpos(String key)throws Exception{
    String u="https://www.alphavantage.co/query?function=IPO_CALENDAR&apikey="+URLEncoder.encode(key,StandardCharsets.UTF_8);
    HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();
    HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(20)).header("User-Agent","MarketLedger/3.1").GET().build(),HttpResponse.BodyHandlers.ofString());
    String body=r.body()==null?"":r.body().trim(), ct=r.headers().firstValue("content-type").orElse("");
    if(r.statusCode()!=200)throw new IOException("HTTP "+r.statusCode());
    if(body.isBlank())throw new IOException("empty response");
    if(body.startsWith("{"))throw new IOException("provider notice: "+body.replaceAll("\\s+"," ").substring(0,Math.min(180,body.length())));
    String[] lines=body.split("\\R"); String header=lines.length>0?lines[0]:"";
    if(lines.length<1||!header.contains(","))throw new IOException("unexpected non-CSV response");
    String[] hdr=parseCsvLine(header); int si=col(hdr,"symbol"),ni=col(hdr,"name"),di=col(hdr,"ipoDate","ipo_date","date"),lo=col(hdr,"priceRangeLow","price_range_low"),hi=col(hdr,"priceRangeHigh","price_range_high");
    if(di<0){LAST_IPO_DIAG=new ProviderDiag("Alpha Vantage",r.statusCode(),ct,header,Math.max(0,lines.length-1),0,0,"missing date column");throw new IOException("CSV missing IPO date column; header="+header);}
    var out=new ArrayList<IpoHit>(); int parsed=0;
    for(int i=1;i<lines.length;i++){
      String[] f=parseCsvLine(lines[i]); if(f.length<=di)continue;
      try{
        LocalDate d=LocalDate.parse(f[di].trim()); parsed++;
        String sym=si>=0&&si<f.length?f[si].trim():"", name=ni>=0&&ni<f.length?f[ni].trim():sym, range="";
        if(lo>=0&&hi>=0&&lo<f.length&&hi<f.length&&!f[lo].isBlank()&&!f[hi].isBlank())range="$"+f[lo].trim()+"–$"+f[hi].trim();
        out.add(new IpoHit(sym,name,d,range,"Alpha Vantage"));
      }catch(Exception ignored){}
    }
    LAST_IPO_DIAG=new ProviderDiag("Alpha Vantage",r.statusCode(),ct,header,Math.max(0,lines.length-1),parsed,out.size(),"valid CSV");
    return out;
  }

  static int col(String[] h,String... names){
    for(int i=0;i<h.length;i++)for(String n:names)
      if(h[i].replace("_","").replace(" ","").equalsIgnoreCase(n.replace("_","").replace(" ","")))return i;
    return -1;
  }

  static String fetchAlphaCsv(String u,String label)throws Exception{
    HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();
    HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(20))
      .header("User-Agent","MarketLedger/3.0").GET().build(),HttpResponse.BodyHandlers.ofString());
    if(r.statusCode()!=200)throw new IOException("HTTP "+r.statusCode());
    String body=r.body()==null?"":r.body().trim();
    if(body.isBlank())throw new IOException("empty response");
    if(body.startsWith("{")){
      String compact=body.replaceAll("\\s+"," ");
      throw new IOException("provider notice: "+compact.substring(0,Math.min(180,compact.length())));
    }
    if(!body.contains(","))throw new IOException("unexpected non-CSV response");
    return body;
  }

  static void startEarningsWorker(){
    var scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"marketledger-earnings");t.setDaemon(true);return t;});
    scheduler.scheduleWithFixedDelay(()->{
      try{
        EarningsSyncResult r=syncUpcomingEarnings();
        System.out.println("Earnings engine: checked="+r.checked+" found="+r.found+" changed="+r.changed+" failed="+r.failed);
      }catch(Throwable e){System.err.println("Earnings engine warning: "+e.getMessage());}
    },45,21600,TimeUnit.SECONDS);
    System.out.println("Earnings engine: scheduled every 6 hours");
  }

  static void syncEarningsEndpoint(HttpExchange x)throws Exception{
    EarningsSyncResult r=syncUpcomingEarnings();
    StringBuilder fs=new StringBuilder("[");
    for(int i=0;i<Math.min(8,r.failures.size());i++){
      if(i>0)fs.append(",");
      fs.append("\"").append(esc(r.failures.get(i))).append("\"");
    }
    fs.append("]");
    boolean av=System.getenv("ALPHA_VANTAGE_API_KEY")!=null&&!System.getenv("ALPHA_VANTAGE_API_KEY").isBlank();
    json(x,200,"{\"ok\":true,\"checked\":"+r.checked+",\"found\":"+r.found+",\"updated\":"+r.changed+",\"failed\":"+r.failed+",\"alphaVantageConfigured\":"+av+",\"failures\":"+fs+"}");
  }

  static EarningsSyncResult syncUpcomingEarnings() throws Exception {
    List<Stock> stocks;
    synchronized(LOCK){stocks=readStocks();}
    var failures=new ArrayList<String>();
    var hits=new ArrayList<EarningsHit>();
    String primary="none";
    boolean primarySucceeded=false;

    try{
      hits.addAll(fetchXoomarEarnings(stocks));
      primary="Xoomar/SEC"; primarySucceeded=true;
    }catch(Exception e){failures.add("Xoomar: "+e.getMessage());}

    String avKey=System.getenv("ALPHA_VANTAGE_API_KEY");
    if(!primarySucceeded && avKey!=null&&!avKey.isBlank()){
      try{hits.addAll(fetchAlphaVantageCalendar(avKey,stocks));primary="Alpha Vantage fallback";primarySucceeded=true;}
      catch(Exception e){failures.add("Alpha Vantage fallback: "+e.getMessage());}
    }

    if(!primarySucceeded){
      try{
        YahooSession ys=openYahooSession();
        for(var st:stocks){
          try{var h=fetchYahooEarnings(ys,st.symbol);if(h!=null)hits.add(h);}
          catch(Exception e){failures.add("Yahoo "+st.symbol+": "+e.getMessage());}
        }
        if(!hits.isEmpty())primary="Yahoo fallback";
      }catch(Exception e){failures.add("Yahoo fallback: "+e.getMessage());}
    }

    int changed=0;
    synchronized(LOCK){
      var es=dedupeEvents(readEvents());
      int before=es.size();
      long next=es.stream().mapToLong(Event::id).max().orElse(0)+1;
      long nowMs=System.currentTimeMillis(), horizon=nowMs+60L*24*60*60*1000;
      for(var h:hits){
        long eventMs=h.epoch*1000L;
        if(eventMs<nowMs-12L*60*60*1000||eventMs>horizon)continue;
        String day=Instant.ofEpochSecond(h.epoch).atZone(ZoneId.of("America/New_York")).toLocalDate().toString();
        String when=day+" 12:00";
        String title="Upcoming earnings • Time TBD • "+(h.estimated?"estimated date":"reported calendar date")+" • auto-synced • "+h.source;
        Event old=es.stream().filter(e->e.type.equalsIgnoreCase("EARNINGS")&&e.scope.equalsIgnoreCase(h.symbol)&&e.title.contains("auto-synced")).findFirst().orElse(null);
        if(old!=null){
          if(!old.when.equals(when)||!old.title.equals(title)){
            int idx=es.indexOf(old);es.set(idx,new Event(old.id,when,"EARNINGS","HIGH",h.symbol,title,old.created));changed++;
          }
        }else{
          es.add(new Event(next++,when,"EARNINGS","HIGH",h.symbol,title,now()));changed++;
        }
      }
      es=dedupeEvents(es);
      if(es.size()<before)changed+=before-es.size();
      es.sort(Comparator.comparing(Event::when));
      writeEvents(es); // includes cached auto-synced earnings when providers temporarily fail
    }
    if(hits.isEmpty()&&primary.equals("none"))failures.add("No provider returned usable earnings dates; cached events preserved");
    return new EarningsSyncResult(stocks.size(),hits.size(),changed,failures.size(),failures);
  }

  static String jsonString(String obj,String key){
    var m=java.util.regex.Pattern.compile("\\\""+java.util.regex.Pattern.quote(key)+"\\\"\\s*:\\s*(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|null)",java.util.regex.Pattern.DOTALL).matcher(obj);
    if(!m.find()||m.group(1)==null)return "";
    return m.group(1).replace("\\\"","\"").replace("\\/","/").replace("\\\\","\\");
  }
  static List<String> jsonObjects(String json){
    var out=new ArrayList<String>(); int depth=0,start=-1; boolean q=false,escp=false;
    for(int i=0;i<json.length();i++){
      char ch=json.charAt(i);
      if(q){if(escp)escp=false;else if(ch=='\\')escp=true;else if(ch=='"')q=false;continue;}
      if(ch=='"'){q=true;continue;}
      if(ch=='{'){if(depth==0)start=i;depth++;}
      else if(ch=='}'&&depth>0){depth--;if(depth==0&&start>=0){out.add(json.substring(start,i+1));start=-1;}}
    }
    return out;
  }

  static List<EarningsHit> fetchXoomarEarnings(List<Stock> stocks)throws Exception{
    var wanted=new HashSet<String>();for(var st:stocks)wanted.add(st.symbol.toUpperCase(Locale.ROOT));
    LocalDate from=LocalDate.now(ZoneId.of("America/New_York")),to=from.plusDays(60);
    String u="https://xoomar.com/api/markets/earnings?from="+from+"&to="+to+"&limit=2000";
    HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();
    HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(20))
      .header("User-Agent","MarketLedger/3.2").header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
    String body=r.body()==null?"":r.body(),ct=r.headers().firstValue("content-type").orElse("");
    if(r.statusCode()!=200)throw new IOException("HTTP "+r.statusCode());
    var objs=jsonObjects(body);var out=new ArrayList<EarningsHit>();int parsed=0;
    for(String o:objs){
      String ticker=jsonString(o,"ticker"),date=jsonString(o,"date"),status=jsonString(o,"status");
      if(ticker.isBlank()||date.isBlank())continue;
      try{
        LocalDate d=LocalDate.parse(date.substring(0,10));parsed++;
        String sym=ticker.toUpperCase(Locale.ROOT);if(!wanted.contains(sym))continue;
        long epoch=d.atTime(12,0).atZone(ZoneId.of("America/New_York")).toEpochSecond();
        out.add(new EarningsHit(sym,epoch,!status.equalsIgnoreCase("reported"),"Xoomar/SEC"));
      }catch(Exception ignored){}
    }
    LAST_EARNINGS_DIAG=new ProviderDiag("Xoomar/SEC",r.statusCode(),ct,"JSON: date,ticker,company,status",parsed,parsed,out.size(),"valid JSON");
    return out;
  }

  static List<IpoHit> fetchNasdaqIpos()throws Exception{
    var all=new ArrayList<IpoHit>();int rows=0,parsed=0,http=200;String ct="";
    YearMonth ym=YearMonth.now(ZoneId.of("America/New_York"));
    HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();
    for(int k=0;k<4;k++){
      String month=ym.plusMonths(k).toString();
      String u="https://api.nasdaq.com/api/ipo/calendar?date="+month;
      HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(20))
        .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/123 Safari/537.36")
        .header("Accept","application/json, text/plain, */*").header("Referer","https://www.nasdaq.com/market-activity/ipos").GET().build(),HttpResponse.BodyHandlers.ofString());
      http=r.statusCode();ct=r.headers().firstValue("content-type").orElse(ct);
      if(http!=200)throw new IOException("HTTP "+http+" for "+month);
      String body=r.body()==null?"":r.body();
      for(String o:jsonObjects(body)){
        String date=jsonString(o,"expectedPriceDate");
        if(date.isBlank())continue;
        rows++;
        try{
          LocalDate d;
          try{d=LocalDate.parse(date);}
          catch(Exception x){d=LocalDate.parse(date,java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy"));}
          String sym=jsonString(o,"proposedTickerSymbol");
          String name=jsonString(o,"companyName");
          String price=jsonString(o,"proposedSharePrice");
          if(name.isBlank())continue;
          parsed++;all.add(new IpoHit(sym,name,d,price,"Nasdaq IPO Calendar"));
        }catch(Exception ignored){}
      }
    }
    // de-duplicate monthly overlaps
    var seen=new HashSet<String>();var out=new ArrayList<IpoHit>();
    for(var h:all)if(seen.add((h.symbol+"|"+h.name+"|"+h.date).toLowerCase(Locale.ROOT)))out.add(h);
    LAST_IPO_DIAG=new ProviderDiag("Nasdaq",http,ct,"JSON: proposedTickerSymbol,companyName,expectedPriceDate,proposedSharePrice",rows,parsed,out.size(),"valid JSON");
    return out;
  }

  static List<EarningsHit> fetchAlphaVantageCalendar(String key,List<Stock> stocks)throws Exception{
    var wanted=new HashSet<String>(); for(var st:stocks)wanted.add(st.symbol.toUpperCase(Locale.ROOT));
    String u="https://www.alphavantage.co/query?function=EARNINGS_CALENDAR&horizon=3month&apikey="+URLEncoder.encode(key,StandardCharsets.UTF_8);
    HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();
    HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(20)).header("User-Agent","MarketLedger/3.1").GET().build(),HttpResponse.BodyHandlers.ofString());
    String body=r.body()==null?"":r.body().trim(), ct=r.headers().firstValue("content-type").orElse("");
    if(r.statusCode()!=200)throw new IOException("HTTP "+r.statusCode());
    if(body.isBlank())throw new IOException("empty response");
    if(body.startsWith("{"))throw new IOException("provider notice: "+body.replaceAll("\\s+"," ").substring(0,Math.min(180,body.length())));
    String[] lines=body.split("\\R"); String header=lines.length>0?lines[0]:"";
    if(lines.length<1||!header.contains(","))throw new IOException("unexpected non-CSV response");
    String[] hdr=parseCsvLine(header); int si=col(hdr,"symbol"), di=col(hdr,"reportDate","report_date","date");
    if(si<0||di<0){LAST_EARNINGS_DIAG=new ProviderDiag("Alpha Vantage",r.statusCode(),ct,header,Math.max(0,lines.length-1),0,0,"missing columns");throw new IOException("CSV missing symbol/reportDate; header="+header);}
    var out=new ArrayList<EarningsHit>(); int parsed=0;
    for(int i=1;i<lines.length;i++){
      String[] f=parseCsvLine(lines[i]); if(f.length<=Math.max(si,di))continue;
      try{
        LocalDate d=LocalDate.parse(f[di].trim()); parsed++;
        String sym=f[si].trim().toUpperCase(Locale.ROOT); if(!wanted.contains(sym))continue;
        long epoch=d.atTime(12,0).atZone(ZoneId.of("America/New_York")).toEpochSecond();
        out.add(new EarningsHit(sym,epoch,true,"Alpha Vantage"));
      }catch(Exception ignored){}
    }
    LAST_EARNINGS_DIAG=new ProviderDiag("Alpha Vantage",r.statusCode(),ct,header,Math.max(0,lines.length-1),parsed,out.size(),"valid CSV");
    return out;
  }

  static String[] parseCsvLine(String line){
    var fields=new ArrayList<String>();var cur=new StringBuilder();boolean q=false;
    for(int i=0;i<line.length();i++){
      char ch=line.charAt(i);
      if(ch=='"'){if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=!q;}
      else if(ch==','&&!q){fields.add(cur.toString());cur.setLength(0);}
      else cur.append(ch);
    }
    fields.add(cur.toString());
    return fields.toArray(String[]::new);
  }

  static ArrayList<Event> dedupeEvents(List<Event> input){
    var out=new ArrayList<Event>();
    var seen=new HashSet<String>();
    for(var e:input){
      String key=(e.when+"|"+e.type+"|"+e.impact+"|"+e.scope+"|"+e.title).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
      if(seen.add(key))out.add(e);
    }
    return out;
  }

  record YahooSession(HttpClient client,String crumb) {}

  static YahooSession openYahooSession() throws Exception {
    CookieManager cm=new CookieManager();
    cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
    HttpClient c=HttpClient.newBuilder().cookieHandler(cm).followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();

    // Bootstrap Yahoo's session cookie, then obtain the crumb required by quoteSummary.
    try{
      c.send(HttpRequest.newBuilder(URI.create("https://fc.yahoo.com"))
        .timeout(java.time.Duration.ofSeconds(8)).header("User-Agent","Mozilla/5.0 MarketLedger/2.7").GET().build(),
        HttpResponse.BodyHandlers.discarding());
    }catch(Exception ignored){}

    HttpResponse<String> cr=c.send(HttpRequest.newBuilder(URI.create("https://query1.finance.yahoo.com/v1/test/getcrumb"))
      .timeout(java.time.Duration.ofSeconds(8)).header("User-Agent","Mozilla/5.0 MarketLedger/2.7").GET().build(),
      HttpResponse.BodyHandlers.ofString());
    if(cr.statusCode()!=200||cr.body()==null||cr.body().isBlank())throw new IOException("crumb HTTP "+cr.statusCode());
    return new YahooSession(c,cr.body().trim());
  }

  static EarningsHit fetchYahooEarnings(YahooSession ys,String sym)throws Exception{
    String enc=URLEncoder.encode(sym,StandardCharsets.UTF_8);
    String crumb=URLEncoder.encode(ys.crumb,StandardCharsets.UTF_8);
    String u="https://query2.finance.yahoo.com/v10/finance/quoteSummary/"+enc+
      "?modules=calendarEvents&formatted=false&corsDomain=finance.yahoo.com&crumb="+crumb;
    HttpResponse<String> r=ys.client.send(HttpRequest.newBuilder(URI.create(u))
      .timeout(java.time.Duration.ofSeconds(10)).header("User-Agent","Mozilla/5.0 MarketLedger/2.7").GET().build(),
      HttpResponse.BodyHandlers.ofString());
    if(r.statusCode()!=200)throw new IOException("HTTP "+r.statusCode());
    String b=r.body();
    int ep=b.indexOf("\"earningsDate\"");
    if(ep<0)return null;
    String tail=b.substring(ep,Math.min(b.length(),ep+1200));

    // Yahoo has returned both raw epoch objects and ISO date-time strings across client generations.
    Long epoch=null;
    var raw=java.util.regex.Pattern.compile("\\\"raw\\\"\\s*:\\s*(\\d{9,12})").matcher(tail);
    if(raw.find())epoch=Long.parseLong(raw.group(1));
    if(epoch==null){
      var iso=java.util.regex.Pattern.compile("\\\"earningsDate\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)\\\"").matcher(tail);
      if(iso.find()){
        String v=iso.group(1);
        try{epoch=Instant.parse(v).getEpochSecond();}
        catch(Exception ex){
          try{epoch=LocalDate.parse(v.substring(0,10)).atStartOfDay(ZoneId.of("America/New_York")).toEpochSecond();}
          catch(Exception ignored){}
        }
      }
    }
    if(epoch==null)return null;
    boolean estimated=tail.matches("(?s).*\\\"isEarningsDateEstimate\\\"\\s*:\\s*true.*");
    return new EarningsHit(sym,epoch,estimated,"Yahoo calendarEvents authenticated");
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
      if(p.equals("/healthz")){json(x,200,"{\"ok\":true,\"database\":"+q(DATABASE_READY?"persistent":"local")+"}");return;}
      if(p.equals("/api/storage/status") && m.equals("GET")){json(x,200,"{\"persistent\":"+DATABASE_READY+",\"mode\":"+q(DATABASE_READY?"PostgreSQL":"temporary local files")+"}");return;}
      if(p.equals("/login") && m.equals("GET")){loginPage(x,"");return;}
      if(p.equals("/login") && m.equals("POST")){login(x);return;}
      if(p.equals("/logout")){logout(x);return;}
      if(!authorized(x)){
        if(p.startsWith("/api/")){json(x,401,"{\"error\":\"Authentication required\"}");return;}
        redirect(x,"/login");return;
      }
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
      if(p.equals("/api/context/earnings/sync") && m.equals("POST")) { syncEarningsEndpoint(x); return; }
      if(p.equals("/api/context/corporate/sync") && m.equals("POST")) { syncCorporateEndpoint(x); return; }
      if(p.equals("/api/context/corporate/diagnostics") && m.equals("GET")) { corporateDiagnosticsEndpoint(x); return; }
      if(p.equals("/api/settings/marketdata") && m.equals("GET")) { marketSettingsGet(x); return; }
      if(p.equals("/api/settings/marketdata") && m.equals("POST")) { marketSettingsSave(x); return; }
      if(p.startsWith("/api/microstructure/") && m.equals("GET")) { microstructure(x,p.substring("/api/microstructure/".length())); return; }
      if(p.matches("/api/notes/\\d+") && m.equals("DELETE")) { deleteNote(x,Long.parseLong(p.split("/")[3])); return; }
      if(p.equals("/api/quotes/refresh") && m.equals("POST")) { refreshQuotes(x); return; }
      if(p.startsWith("/api/market/") && m.equals("GET")) { marketData(x,p.substring("/api/market/".length())); return; }
      if(p.equals("/api/info") && m.equals("GET")) { boolean cloud=System.getenv("RENDER")!=null; json(x,200,"{\"port\":"+PORT+",\"lanIp\":"+q(lanIp())+",\"cloud\":"+cloud+"}"); return; }
      if(p.equals("/api/export") && m.equals("GET")) { exportCsv(x); return; }
      if(p.equals("/api/backup") && m.equals("GET")) { downloadBackup(x); return; }
      if(p.equals("/api/backup/restore") && m.equals("POST")) { restoreBackup(x); return; }
      if(p.equals("/api/migration/windows") && m.equals("POST")) { importWindowsData(x); return; }
      json(x,404,"{\"error\":\"Not found\"}");
    } catch(Exception e) { json(x,400,"{\"error\":"+q(e.getMessage()==null?"Request failed":e.getMessage())+"}"); }
  }

  static boolean authorized(HttpExchange x){
    if(ACCESS_PASSWORD.isBlank()) return true;
    String cookie=x.getRequestHeaders().getFirst("Cookie"); if(cookie==null)return false;
    for(String c:cookie.split(";")){String v=c.trim();if(v.startsWith("ml_session=")&&SESSIONS.contains(v.substring(11)))return true;}
    return false;
  }
  static void loginPage(HttpExchange x,String error)throws IOException{
    String html="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'><meta name='theme-color' content='#071019'><link rel='apple-touch-icon' href='/apple-touch-icon.png'><title>MarketLedger Login</title><style>body{margin:0;background:#071019;color:#eef6ff;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;display:grid;min-height:100vh;place-items:center}.c{width:min(88vw,390px);background:#0e1b29;border:1px solid #24384c;border-radius:20px;padding:28px;box-shadow:0 20px 60px #0008}h1{margin:0 0 6px;font-size:26px}.s{color:#8ea6bd;margin-bottom:24px}input,button{box-sizing:border-box;width:100%;padding:14px;border-radius:12px;font-size:16px}input{background:#08131e;color:white;border:1px solid #31475c;margin:8px 0 14px}button{background:#2f80ed;color:white;border:0;font-weight:700}.e{color:#ff9a9a;margin:8px 0}</style></head><body><div class='c'><h1>MarketLedger Pro</h1><div class='s'>Secure cloud access</div>"+(error.isBlank()?"":"<div class='e'>"+esc(error)+"</div>")+"<form method='post' action='/login'><label>Password</label><input name='password' type='password' autocomplete='current-password' autofocus required><button type='submit'>Open MarketLedger</button></form></div></body></html>";
    bytes(x,200,"text/html; charset=utf-8",html.getBytes(StandardCharsets.UTF_8));
  }
  static void login(HttpExchange x)throws Exception{
    if(ACCESS_PASSWORD.isBlank()){redirect(x,"/");return;}
    String supplied=form(x).getOrDefault("password","");
    if(!java.security.MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),ACCESS_PASSWORD.getBytes(StandardCharsets.UTF_8))){loginPage(x,"Incorrect password");return;}
    byte[] r=new byte[32];new java.security.SecureRandom().nextBytes(r);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(r);SESSIONS.add(token);
    x.getResponseHeaders().add("Set-Cookie","ml_session="+token+"; Path=/; HttpOnly"+secureCookie()+"; SameSite=Strict; Max-Age=2592000");redirect(x,"/");
  }
  static void logout(HttpExchange x)throws IOException{String cookie=x.getRequestHeaders().getFirst("Cookie");if(cookie!=null)for(String c:cookie.split(";")){String v=c.trim();if(v.startsWith("ml_session="))SESSIONS.remove(v.substring(11));}x.getResponseHeaders().add("Set-Cookie","ml_session=; Path=/; HttpOnly"+secureCookie()+"; SameSite=Strict; Max-Age=0");redirect(x,"/login");}
  static String secureCookie(){return (System.getenv("RENDER")!=null||"true".equalsIgnoreCase(System.getenv("MARKETLEDGER_SECURE_COOKIE")))?"; Secure":"";}
  static void redirect(HttpExchange x,String to)throws IOException{x.getResponseHeaders().set("Location",to);x.sendResponseHeaders(302,-1);x.close();}
  static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}

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


  static Properties marketSettings() throws IOException { Properties p=new Properties(); if(Files.exists(SETTINGS)) try(InputStream in=Files.newInputStream(SETTINGS)){p.load(in);} String k=System.getenv().getOrDefault("ALPACA_API_KEY","").trim(), sec=System.getenv().getOrDefault("ALPACA_API_SECRET","").trim(); if(!k.isBlank())p.setProperty("alpaca.key",k); if(!sec.isBlank())p.setProperty("alpaca.secret",sec); if(!k.isBlank()&&!sec.isBlank())p.setProperty("provider","ALPACA"); return p; }
  static String[] alpacaConnection(Properties p){String provider=p.getProperty("provider","YAHOO");String key=p.getProperty("alpaca.key",""),secret=p.getProperty("alpaca.secret","");if(!provider.equals("ALPACA"))return new String[]{"FALLBACK","Yahoo selected"};if(key.isBlank()||secret.isBlank())return new String[]{"MISSING","Alpaca credentials are incomplete"};try{HttpResponse<String> r=alpacaGet("https://data.alpaca.markets/v2/stocks/SPY/snapshot","iex");int c=r.statusCode();if(c==200)return new String[]{"CONNECTED","Authenticated Alpaca IEX snapshot available"};if(c==401||c==403)return new String[]{"AUTH_FAILED","Alpaca rejected the API credentials (HTTP "+c+")"};if(c==429)return new String[]{"RATE_LIMITED","Alpaca rate limit reached; Yahoo candle fallback remains active"};if(c==404||c==204)return new String[]{"NO_DATA","Alpaca authenticated but returned no snapshot data"};return new String[]{"FALLBACK","Alpaca returned HTTP "+c+"; Yahoo fallback remains active"};}catch(Exception e){return new String[]{"FALLBACK","Alpaca connection unavailable: "+(e.getMessage()==null?"request failed":e.getMessage())};}}
  static void marketSettingsGet(HttpExchange x)throws Exception{Properties p=marketSettings();String provider=p.getProperty("provider","YAHOO");boolean configured=!p.getProperty("alpaca.key","").isBlank()&&!p.getProperty("alpaca.secret","").isBlank();String[] c=alpacaConnection(p);json(x,200,"{\"provider\":"+q(provider)+",\"alpacaConfigured\":"+configured+",\"fallback\":\"YAHOO\",\"connection\":"+q(c[0])+",\"detail\":"+q(c[1])+"}");}
  static void marketSettingsSave(HttpExchange x)throws Exception{Map<String,String>f=form(x);Properties p=marketSettings();String provider=f.getOrDefault("provider",p.getProperty("provider","YAHOO")).toUpperCase(Locale.ROOT);if(!provider.equals("YAHOO")&&!provider.equals("ALPACA"))throw new Exception("Provider must be YAHOO or ALPACA");if(CLOUD_MODE){p.remove("alpaca.key");p.remove("alpaca.secret");String ek=System.getenv().getOrDefault("ALPACA_API_KEY","").trim(),es=System.getenv().getOrDefault("ALPACA_API_SECRET","").trim();if(!ek.isBlank()&&!es.isBlank())provider="ALPACA";}else{String key=f.getOrDefault("key","").trim(),secret=f.getOrDefault("secret","").trim();if(!key.isBlank())p.setProperty("alpaca.key",key);if(!secret.isBlank())p.setProperty("alpaca.secret",secret);if(!key.isBlank()&&!secret.isBlank())provider="ALPACA";}p.setProperty("provider",provider);Files.createDirectories(DATA);Path tmp=SETTINGS.resolveSibling("settings.properties.tmp");Properties safe=new Properties();safe.setProperty("provider",provider);try(OutputStream out=Files.newOutputStream(tmp)){safe.store(out,"MarketLedger Pro non-secret settings");}try{Files.move(tmp,SETTINGS,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,SETTINGS,StandardCopyOption.REPLACE_EXISTING);}if(!CLOUD_MODE)persistFile(SETTINGS);else sanitizeCloudSettings();p=marketSettings();String[] c=alpacaConnection(p);boolean configured=!p.getProperty("alpaca.key","").isBlank()&&!p.getProperty("alpaca.secret","").isBlank();json(x,200,"{\"ok\":true,\"provider\":"+q(p.getProperty("provider","YAHOO"))+",\"alpacaConfigured\":"+configured+",\"connection\":"+q(c[0])+",\"detail\":"+q(c[1])+"}");}
  static HttpResponse<String> alpacaGet(String url,String feed)throws Exception{Properties p=marketSettings();String key=p.getProperty("alpaca.key",""),secret=p.getProperty("alpaca.secret","");if(key.isBlank()||secret.isBlank())throw new Exception("Alpaca API key/secret not configured");String u=url+(url.contains("?")?"&":"?")+"feed="+URLEncoder.encode(feed,StandardCharsets.UTF_8);HttpClient c=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();return c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(12)).header("APCA-API-KEY-ID",key).header("APCA-API-SECRET-KEY",secret).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());}
  static String jsonNum(String body,String key){var m=java.util.regex.Pattern.compile("\\\""+key+"\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(body);return m.find()?m.group(1):"null";}
  static String jsonStr(String body,String key){var m=java.util.regex.Pattern.compile("\\\""+key+"\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);return m.find()?m.group(1):"";}
  static double dnum(String s){try{return s==null||s.equals("null")?Double.NaN:Double.parseDouble(s);}catch(Exception e){return Double.NaN;}}
  static String feedForPhase(String phase){return phase.equals("OVERNIGHT")?"overnight":"iex";}
  static void microstructure(HttpExchange x,String raw)throws Exception{
    String sym=raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.-]","");if(sym.isBlank())throw new Exception("Invalid ticker");
    Properties p=marketSettings();String provider=p.getProperty("provider","YAHOO");if(!provider.equals("ALPACA")){json(x,200,"{\"provider\":\"YAHOO\",\"available\":false,\"reason\":\"Configure Alpaca for bid/ask and overnight liquidity\"}");return;}
    String phase=marketPhaseServer(),feed=feedForPhase(phase),enc=URLEncoder.encode(sym,StandardCharsets.UTF_8);
    HttpResponse<String> qr=alpacaGet("https://data.alpaca.markets/v2/stocks/"+enc+"/quotes/latest",feed);
    HttpResponse<String> br=alpacaGet("https://data.alpaca.markets/v2/stocks/"+enc+"/bars/latest",feed);
    if(qr.statusCode()!=200&&br.statusCode()!=200){json(x,200,"{\"provider\":\"ALPACA\",\"available\":false,\"feed\":"+q(feed)+",\"http\":"+Math.max(qr.statusCode(),br.statusCode())+",\"reason\":\"No latest quote/bar from selected feed\"}");return;}
    String qb=qr.statusCode()==200?qr.body():"",bb=br.statusCode()==200?br.body():"";
    String bid=jsonNum(qb,"bp"),ask=jsonNum(qb,"ap"),bs=jsonNum(qb,"bs"),as=jsonNum(qb,"as"),last=jsonNum(bb,"c"),barVol=jsonNum(bb,"v");
    String quoteTs=jsonStr(qb,"t"),barTs=jsonStr(bb,"t"); double bd=dnum(bid),ad=dnum(ask),ld=dnum(last); if(Double.isNaN(ld)&&!Double.isNaN(bd)&&!Double.isNaN(ad))last=String.format(Locale.US,"%.6f",(bd+ad)/2.0);
    boolean available=!Double.isNaN(dnum(last))||(!Double.isNaN(bd)&&!Double.isNaN(ad));
    json(x,200,"{\"provider\":\"ALPACA\",\"available\":"+available+",\"feed\":"+q(feed)+",\"phase\":"+q(phase)+",\"bid\":"+bid+",\"ask\":"+ask+",\"bidSize\":"+bs+",\"askSize\":"+as+",\"last\":"+last+",\"minuteVolume\":"+barVol+",\"quoteTs\":"+q(quoteTs)+",\"barTs\":"+q(barTs)+"}");
  }
  static String marketPhaseServer(){
    ZonedDateTime z=ZonedDateTime.now(ZoneId.of("America/New_York"));int m=z.getHour()*60+z.getMinute();DayOfWeek d=z.getDayOfWeek();
    if(d==DayOfWeek.SATURDAY)return "CLOSED"; if(d==DayOfWeek.SUNDAY)return m>=1200?"OVERNIGHT":"CLOSED"; if(d==DayOfWeek.FRIDAY&&m>=1200)return "CLOSED";
    if(m<240||m>=1200)return "OVERNIGHT";if(m<570)return "PRE";if(m<960)return "REGULAR";return "POST";
  }
  static String alpacaYahooFallback(String sym)throws Exception{
    String enc=URLEncoder.encode(sym,StandardCharsets.UTF_8);ZonedDateTime end=ZonedDateTime.now(ZoneOffset.UTC),start=end.minusDays(7);
    String u="https://data.alpaca.markets/v2/stocks/"+enc+"/bars?timeframe=5Min&start="+URLEncoder.encode(start.toInstant().toString(),StandardCharsets.UTF_8)+"&end="+URLEncoder.encode(end.toInstant().toString(),StandardCharsets.UTF_8)+"&limit=1000&adjustment=raw";
    HttpResponse<String> r=alpacaGet(u,"iex");if(r.statusCode()!=200)throw new Exception("Alpaca candle fallback HTTP "+r.statusCode());String b=r.body();
    var pat=java.util.regex.Pattern.compile("\\{\\\"c\\\":(-?[0-9.]+),\\\"h\\\":(-?[0-9.]+),\\\"l\\\":(-?[0-9.]+),\\\"n\\\":[0-9]+,\\\"o\\\":(-?[0-9.]+),\\\"t\\\":\\\"([^\\\"]+)\\\",\\\"v\\\":(-?[0-9.]+)");var m=pat.matcher(b);
    StringBuilder ts=new StringBuilder(),op=new StringBuilder(),hi=new StringBuilder(),lo=new StringBuilder(),cl=new StringBuilder(),vo=new StringBuilder();int n=0;
    while(m.find()){if(n++>0){ts.append(',');op.append(',');hi.append(',');lo.append(',');cl.append(',');vo.append(',');}long epoch=Instant.parse(m.group(5)).getEpochSecond();ts.append(epoch);cl.append(m.group(1));hi.append(m.group(2));lo.append(m.group(3));op.append(m.group(4));vo.append(m.group(6));}
    if(n<10)throw new Exception("No usable Alpaca candles for "+sym);
    return "{\"chart\":{\"result\":[{\"meta\":{\"symbol\":"+q(sym)+",\"provider\":\"ALPACA_IEX_FALLBACK\"},\"timestamp\":["+ts+"],\"indicators\":{\"quote\":[{\"open\":["+op+"],\"high\":["+hi+"],\"low\":["+lo+"],\"close\":["+cl+"],\"volume\":["+vo+"]}]}}],\"error\":null}}";
  }
  static void marketData(HttpExchange x,String raw)throws Exception{
    String sym=raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.^=-]",""); if(sym.isBlank()) throw new Exception("Invalid ticker");
    String u="https://query1.finance.yahoo.com/v8/finance/chart/"+URLEncoder.encode(sym,StandardCharsets.UTF_8)+"?range=5d&interval=5m&includePrePost=true&events=div%2Csplits";
    try{HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();HttpResponse<String> r=client.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(10)).header("User-Agent","Mozilla/5.0 MarketLedger/2.5").header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());String body=r.body();if(r.statusCode()==200&&body!=null&&!body.contains("\\\"result\\\":null")&&body.contains("\\\"timestamp\\\"")){bytes(x,200,"application/json; charset=utf-8",body.getBytes(StandardCharsets.UTF_8));return;}}catch(Exception ignored){}
    try{String body=alpacaYahooFallback(sym);bytes(x,200,"application/json; charset=utf-8",body.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new Exception("No candle data from Yahoo or Alpaca fallback for "+sym+": "+e.getMessage());}
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
  static void atomic(Path p,List<String>l)throws IOException{Path t=p.resolveSibling(p.getFileName()+".tmp");Files.write(t,l,StandardCharsets.UTF_8);try{Files.move(t,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){Files.move(t,p,StandardCopyOption.REPLACE_EXISTING);}persistFile(p);if(DATABASE_READY)syncNativeTables();}

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
  static final List<String> BACKUP_FILES=List.of("stocks.tsv","calls.tsv","notes.tsv","events.tsv","signals.tsv");
  static void downloadBackup(HttpExchange x)throws IOException{
    ByteArrayOutputStream bout=new ByteArrayOutputStream();
    try(ZipOutputStream z=new ZipOutputStream(bout,StandardCharsets.UTF_8)){
      for(String name:BACKUP_FILES){Path f=DATA.resolve(name);if(!Files.exists(f))continue;z.putNextEntry(new ZipEntry(name));Files.copy(f,z);z.closeEntry();}
      z.putNextEntry(new ZipEntry("BACKUP-INFO.txt"));
      z.write(("MarketLedger Pro cloud data backup\nCreated: "+Instant.now()+"\nSecrets/API credentials are intentionally excluded.\n").getBytes(StandardCharsets.UTF_8));z.closeEntry();
    }
    byte[] b=bout.toByteArray();
    x.getResponseHeaders().set("Content-Type","application/zip");
    x.getResponseHeaders().set("Content-Disposition","attachment; filename=MarketLedger-Pro-Backup-"+LocalDate.now()+".zip");
    x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();
  }
  static void restoreBackup(HttpExchange x)throws Exception{
    int max=25*1024*1024; byte[] raw=x.getRequestBody().readNBytes(max+1); if(raw.length>max)throw new Exception("Backup is larger than 25 MB");
    Map<String,byte[]> incoming=new HashMap<>();
    try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(raw),StandardCharsets.UTF_8)){
      ZipEntry e; while((e=z.getNextEntry())!=null){String name=Paths.get(e.getName()).getFileName().toString();if(BACKUP_FILES.contains(name)){byte[] data=z.readNBytes(max+1);if(data.length>max)throw new Exception("Backup entry too large");incoming.put(name,data);}z.closeEntry();}
    }
    if(incoming.isEmpty())throw new Exception("No MarketLedger data files found in backup");
    synchronized(LOCK){backupData();for(var e:incoming.entrySet()){Path dst=DATA.resolve(e.getKey()),tmp=DATA.resolve(e.getKey()+".restore.tmp");Files.write(tmp,e.getValue());try{Files.move(tmp,dst,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,dst,StandardCopyOption.REPLACE_EXISTING);}persistFile(dst);}}
    json(x,200,"{\"ok\":true,\"restored\":"+incoming.size()+"}");
  }

  static void importWindowsData(HttpExchange x)throws Exception{
    int max=50*1024*1024; byte[] raw=x.getRequestBody().readNBytes(max+1); if(raw.length>max)throw new Exception("Migration ZIP is larger than 50 MB");
    Map<String,byte[]> incoming=new HashMap<>();
    try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(raw),StandardCharsets.UTF_8)){
      ZipEntry e; while((e=z.getNextEntry())!=null){String name=Paths.get(e.getName()).getFileName().toString();if(BACKUP_FILES.contains(name)){byte[] data=z.readNBytes(max+1);if(data.length>max)throw new Exception("Migration entry too large");incoming.put(name,data);}z.closeEntry();}
    }
    if(incoming.isEmpty())throw new Exception("No MarketLedger Windows data files found in ZIP");
    int stocks=0,calls=0,notes=0,events=0,signals=0;
    synchronized(LOCK){
      backupData();
      if(incoming.containsKey("stocks.tsv")) stocks=mergeStocks(incoming.get("stocks.tsv"));
      if(incoming.containsKey("calls.tsv")) calls=mergeIdFile(CALLS,incoming.get("calls.tsv"));
      if(incoming.containsKey("notes.tsv")) notes=mergeIdFile(NOTES,incoming.get("notes.tsv"));
      if(incoming.containsKey("events.tsv")) events=mergeIdFile(EVENTS,incoming.get("events.tsv"));
      if(incoming.containsKey("signals.tsv")) signals=mergeSignals(incoming.get("signals.tsv"));
      for(Path f:List.of(STOCKS,CALLS,NOTES,EVENTS,SIGNALS)) if(Files.exists(f))persistFile(f);
    }
    json(x,200,"{\"ok\":true,\"stocks\":"+stocks+",\"calls\":"+calls+",\"notes\":"+notes+",\"events\":"+events+",\"signals\":"+signals+",\"message\":\"Windows data merged into PostgreSQL\"}");
  }
  static List<String> cleanLines(byte[] b){
    String t=new String(b,StandardCharsets.UTF_8); List<String> out=new ArrayList<>();
    for(String line:t.split("\\R"))if(!line.isBlank())out.add(line); return out;
  }
  static void writeLines(Path f,List<String> lines)throws IOException{
    String text=lines.isEmpty()?"":String.join(System.lineSeparator(),lines)+System.lineSeparator();
    Path tmp=DATA.resolve(f.getFileName()+".merge.tmp");Files.writeString(tmp,text,StandardCharsets.UTF_8);
    try{Files.move(tmp,f,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,f,StandardCopyOption.REPLACE_EXISTING);}
  }
  static int mergeStocks(byte[] b)throws IOException{
    List<String> cur=Files.exists(STOCKS)?Files.readAllLines(STOCKS,StandardCharsets.UTF_8):new ArrayList<>();Set<String> keys=new HashSet<>();
    for(String l:cur){String[] a=l.split("\\t",-1);if(a.length>0)keys.add(un(a[0]).toUpperCase(Locale.ROOT));}
    int added=0;for(String l:cleanLines(b)){String[] a=l.split("\\t",-1);if(a.length<1)continue;String k=un(a[0]).toUpperCase(Locale.ROOT);if(!k.isBlank()&&keys.add(k)){cur.add(l);added++;}}
    writeLines(STOCKS,cur);return added;
  }
  static int mergeSignals(byte[] b)throws IOException{
    List<String> cur=Files.exists(SIGNALS)?Files.readAllLines(SIGNALS,StandardCharsets.UTF_8):new ArrayList<>();Set<String> keys=new HashSet<>();long maxId=0;
    for(String l:cur){String[] a=l.split("\\t",-1);if(a.length>2){keys.add(un(a[1]).toUpperCase(Locale.ROOT)+"|"+a[2]);try{maxId=Math.max(maxId,Long.parseLong(a[0]));}catch(Exception ignored){}}}
    int added=0;for(String l:cleanLines(b)){String[] a=l.split("\\t",-1);if(a.length<3)continue;String k=un(a[1]).toUpperCase(Locale.ROOT)+"|"+a[2];if(keys.add(k)){a[0]=Long.toString(++maxId);cur.add(String.join("\t",a));added++;}}
    writeLines(SIGNALS,cur);return added;
  }
  static int mergeIdFile(Path f,byte[] b)throws IOException{
    List<String> cur=Files.exists(f)?Files.readAllLines(f,StandardCharsets.UTF_8):new ArrayList<>();Set<String> bodies=new HashSet<>();long maxId=0;
    for(String l:cur){String[] a=l.split("\\t",-1);if(a.length>0){try{maxId=Math.max(maxId,Long.parseLong(a[0]));}catch(Exception ignored){}bodies.add(l.substring(l.indexOf('\t')>=0?l.indexOf('\t')+1:l.length()));}}
    int added=0;for(String l:cleanLines(b)){int tab=l.indexOf('\t');String body=tab>=0?l.substring(tab+1):l;if(!body.isBlank()&&bodies.add(body)){cur.add((++maxId)+"\t"+body);added++;}}
    writeLines(f,cur);return added;
  }

  static byte[] resource(String n)throws IOException{try(InputStream in=MarketLedger.class.getResourceAsStream(n)){if(in==null)throw new FileNotFoundException(n);return in.readAllBytes();}}
  static void bytes(HttpExchange x,int code,String type,byte[] b)throws IOException{x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);x.close();}
  static void json(HttpExchange x,int code,String s)throws IOException{bytes(x,code,"application/json; charset=utf-8",s.getBytes(StandardCharsets.UTF_8));}
  static void ok(HttpExchange x)throws IOException{json(x,200,"{\"ok\":true}");}
}
