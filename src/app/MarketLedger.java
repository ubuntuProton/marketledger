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

  // V5O.2 approved monitoring universe. This is merged into the persistent watchlist
  // on startup so cloud redeploys cannot leave the technical board at the old 31-name set.
  static final List<String[]> V5O_MONITORING_UNIVERSE = List.of(
    new String[]{"META","Meta Platforms"}, new String[]{"MSFT","Microsoft"}, new String[]{"AMZN","Amazon"},
    new String[]{"GOOGL","Alphabet"}, new String[]{"ORCL","Oracle"}, new String[]{"PLTR","Palantir"},
    new String[]{"QCOM","Qualcomm"}, new String[]{"ASML","ASML Holding"}, new String[]{"LRCX","Lam Research"},
    new String[]{"KLAC","KLA"}, new String[]{"AMAT","Applied Materials"}, new String[]{"ANET","Arista Networks"},
    new String[]{"DELL","Dell Technologies"}, new String[]{"VRT","Vertiv"}, new String[]{"CEG","Constellation Energy"},
    new String[]{"NVT","nVent Electric"}, new String[]{"GNRC","Generac"}, new String[]{"CIEN","Ciena"},
    new String[]{"LITE","Lumentum"}, new String[]{"CSCO","Cisco"}, new String[]{"CRWD","CrowdStrike"},
    new String[]{"PANW","Palo Alto Networks"}, new String[]{"ZS","Zscaler"}, new String[]{"FTNT","Fortinet"},
    new String[]{"NET","Cloudflare"}, new String[]{"OKTA","Okta"}, new String[]{"MRVL","Marvell"},
    new String[]{"HPE","Hewlett Packard Enterprise"}, new String[]{"SMCI","Super Micro Computer"}
  );

  record Stock(String symbol,String name,double price,double prev,String updated) {}
  record Call(long id,String symbol,String direction,double baseline,double threshold,String due,String note,String status,double resolved,double move,String created,String resolvedAt) {}
  record Note(long id,String title,String body,String tag,String created) {}
  record Event(long id,String when,String type,String impact,String scope,String title,String created) {}
  record Signal(long id,String symbol,long candleTs,String captured,double price,String signal,int score,String phase,double rsi,double vwap,double trend,double volRatio,double atrPct,double spreadPct,String marketText,String eventText,double r15,double r30,double r60) {}
  record NewsItem(String title,String link,String source,String published,List<String> symbols,String theme,int sourceScore,int relevanceScore,int catalystScore,String catalystClass,String catalystReason,String novelty,String provenance,String evidence,String materiality,String directness,int sourceCount,List<String> sources) {}
  static volatile String NEWS_CACHE_JSON = "{\"items\":[],\"themes\":[],\"updated\":null}";
  static volatile long NEWS_CACHE_AT = 0L;
  static volatile String NEWS_CACHE_KEY = "";

  public static void main(String[] args) throws Exception {
    Files.createDirectories(DATA); Files.createDirectories(BACKUPS); initDatabase(); hydrateFromDatabase(); migrateLegacyData(); seed();
    int universeAdded=ensureV5OMonitoringUniverse();
    syncAllDataToDatabase(); backupData();
    System.out.println("V5O monitoring universe: "+readStocks().size()+" persistent watchlist tickers"+(universeAdded>0?" ("+universeAdded+" added at startup)":""));
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
      try(PreparedStatement ps=c.prepareStatement("INSERT INTO watchlist(symbol,name,price,prev_price,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(symbol) DO UPDATE SET name=EXCLUDED.name,price=EXCLUDED.price,prev_price=EXCLUDED.prev_price,updated_at=EXCLUDED.updated_at")){for(var z:readStocks()){ps.setString(1,z.symbol);ps.setString(2,z.name);ps.setDouble(3,z.price);ps.setDouble(4,z.prev);ps.setString(5,z.updated);ps.addBatch();}ps.executeBatch();}
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
        int n=1; for(int i=0;i<3;i++){if(px[i]==null){u.setNull(n++,Types.DOUBLE);u.setNull(n++,Types.DOUBLE);u.setNull(n++,Types.TIMESTAMP_WITH_TIMEZONE);}else{u.setDouble(n++,px[i]);u.setDouble(n++,(px[i]/p.price()-1)*100);u.setTimestamp(n++,java.sql.Timestamp.from(at[i]));}} if(Double.isNaN(maxGain))u.setNull(n++,Types.DOUBLE);else u.setDouble(n++,maxGain); if(Double.isNaN(maxDraw))u.setNull(n++,Types.DOUBLE);else u.setDouble(n++,maxDraw);u.setLong(n,p.id());updated+=u.executeUpdate();}
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
  record ConfirmedEarnings(String symbol,LocalDate date,String timing,String source) {}
  record EarningsCandidate(String symbol,LocalDate date,String timing,String source,boolean authoritative) {}
  record VerificationResult(String symbol,String status,LocalDate selectedDate,String timing,String source,String note) {}
  static volatile Map<String,VerificationResult> LAST_EARNINGS_VERIFICATION=new LinkedHashMap<>();

  static final Map<String,ConfirmedEarnings> CONFIRMED_EARNINGS=Map.of(
    "MU",new ConfirmedEarnings("MU",LocalDate.of(2026,9,30),"AMC","Micron Investor Relations")
  );
  static String earningsConfidence(EarningsHit h){
    var v=LAST_EARNINGS_VERIFICATION.get(h.symbol.toUpperCase(Locale.ROOT));
    if(v!=null)return v.status;
    return h.estimated?"ESTIMATED":"CONFIRMED";
  }
  static String earningsTiming(EarningsHit h){
    var v=LAST_EARNINGS_VERIFICATION.get(h.symbol.toUpperCase(Locale.ROOT));
    if(v!=null && v.timing!=null && !v.timing.isBlank())return v.timing;
    ConfirmedEarnings c=CONFIRMED_EARNINGS.get(h.symbol.toUpperCase(Locale.ROOT));
    return c==null?"TBD":c.timing;
  }
  static String earningsEventTitle(EarningsHit h){
    return earningsConfidence(h)+" earnings • "+h.symbol+" • timing="+earningsTiming(h)+" • source="+h.source+" • auto-synced";
  }
  static String earningsImpact(EarningsHit h){
    LocalDate event=Instant.ofEpochSecond(h.epoch).atZone(ZoneId.of("America/New_York")).toLocalDate();
    long days=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(ZoneId.of("America/New_York")),event);
    // High is reserved for the near-event window. Estimates farther out are context only.
    if(!h.estimated && days<=1)return "HIGH";
    if(h.estimated && days<=1)return "MEDIUM";
    return "LOW";
  }

  static Map<String,VerificationResult> reconcileEarnings(List<EarningsHit> hits){
    Map<String,List<EarningsCandidate>> by=new LinkedHashMap<>();
    for(var h:hits){
      LocalDate d=Instant.ofEpochSecond(h.epoch).atZone(ZoneId.of("America/New_York")).toLocalDate();
      by.computeIfAbsent(h.symbol.toUpperCase(Locale.ROOT),k->new ArrayList<>())
        .add(new EarningsCandidate(h.symbol.toUpperCase(Locale.ROOT),d,"TBD",h.source,false));
    }
    // Company/IR confirmations are injected as highest-authority candidates.
    for(var ce:CONFIRMED_EARNINGS.values())
      by.computeIfAbsent(ce.symbol.toUpperCase(Locale.ROOT),k->new ArrayList<>())
        .add(new EarningsCandidate(ce.symbol.toUpperCase(Locale.ROOT),ce.date,ce.timing,ce.source,true));

    Map<String,VerificationResult> out=new LinkedHashMap<>();
    for(var en:by.entrySet()){
      String sym=en.getKey(); var cs=en.getValue();
      var auth=cs.stream().filter(EarningsCandidate::authoritative).findFirst();
      if(auth.isPresent()){
        var a=auth.get();
        var conflicts=new LinkedHashSet<String>();
        for(var c:cs)if(!c.authoritative && !c.date.equals(a.date))conflicts.add(c.source+"="+c.date);
        out.put(sym,new VerificationResult(sym,"CONFIRMED",a.date,a.timing,a.source,
          conflicts.isEmpty()?"authoritative company/IR date":"authoritative date retained; provider conflict: "+String.join(", ",conflicts)));
        continue;
      }
      Map<LocalDate,Set<String>> votes=new LinkedHashMap<>();
      for(var c:cs)votes.computeIfAbsent(c.date,k->new LinkedHashSet<>()).add(c.source);
      LocalDate best=null; Set<String> sources=Set.of();
      for(var v:votes.entrySet())if(v.getValue().size()>sources.size()){best=v.getKey();sources=v.getValue();}
      if(best!=null && sources.size()>=2)
        out.put(sym,new VerificationResult(sym,"CORROBORATED",best,"TBD",String.join(" + ",sources),
          "two independent calendar sources agree; not company-confirmed"));
      else if(best!=null)
        out.put(sym,new VerificationResult(sym,"ESTIMATED",best,"TBD",String.join(" + ",sources),
          votes.size()>1?"provider dates conflict; estimate retained":"single-source estimate"));
    }
    LAST_EARNINGS_VERIFICATION=out;
    return out;
  }

  static List<EarningsHit> applyVerification(List<EarningsHit> hits){
    var vr=reconcileEarnings(hits); var out=new ArrayList<EarningsHit>(); var done=new HashSet<String>();
    for(var h:hits){
      String sym=h.symbol.toUpperCase(Locale.ROOT);
      if(!done.add(sym))continue;
      var v=vr.get(sym);
      if(v==null){out.add(h);continue;}
      long epoch=v.selectedDate.atTime("AMC".equals(v.timing)?java.time.LocalTime.of(16,30):"BMO".equals(v.timing)?java.time.LocalTime.of(8,0):java.time.LocalTime.NOON)
        .atZone(ZoneId.of("America/New_York")).toEpochSecond();
      boolean estimated=!"CONFIRMED".equals(v.status);
      out.add(new EarningsHit(sym,epoch,estimated,v.source));
    }
    // Authoritative symbols may not have appeared in a temporarily failed provider result.
    for(var v:vr.values())if("CONFIRMED".equals(v.status)&&done.add(v.symbol)){
      long epoch=v.selectedDate.atTime("AMC".equals(v.timing)?java.time.LocalTime.of(16,30):"BMO".equals(v.timing)?java.time.LocalTime.of(8,0):java.time.LocalTime.NOON)
        .atZone(ZoneId.of("America/New_York")).toEpochSecond();
      out.add(new EarningsHit(v.symbol,epoch,false,v.source));
    }
    return out;
  }

  static EarningsHit applyConfirmedEarnings(EarningsHit h){
    ConfirmedEarnings c=CONFIRMED_EARNINGS.get(h.symbol.toUpperCase(Locale.ROOT));
    if(c==null)return h;
    long epoch=c.date.atTime(16,30).atZone(ZoneId.of("America/New_York")).toEpochSecond();
    return new EarningsHit(h.symbol,epoch,false,c.source);
  }
  record EarningsSyncResult(int checked,int found,int changed,int failed,List<String> failures) {}
  record IpoHit(String symbol,String name,LocalDate date,String priceRange,String source) {}
  record ProviderDiag(String provider,int http,String contentType,String header,int rows,int parsed,int matches,String note) {}
  static volatile ProviderDiag LAST_EARNINGS_DIAG=new ProviderDiag("none",0,"","",0,0,0,"not synced");
  static volatile ProviderDiag LAST_IPO_DIAG=new ProviderDiag("none",0,"","",0,0,0,"not synced");
  static volatile ProviderDiag LAST_XOOMAR_DIAG=new ProviderDiag("Xoomar/SEC",0,"","",0,0,0,"not synced");
  static volatile ProviderDiag LAST_AV_EARNINGS_DIAG=new ProviderDiag("Alpha Vantage",0,"","",0,0,0,"not synced");
  static volatile ProviderDiag LAST_NASDAQ_IPO_DIAG=new ProviderDiag("Nasdaq",0,"","",0,0,0,"not synced");
  static volatile ProviderDiag LAST_AV_IPO_DIAG=new ProviderDiag("Alpha Vantage",0,"","",0,0,0,"not synced");
  record CorporateSyncResult(EarningsSyncResult earnings,int ipoFound,int ipoChanged,int ipoFailed,List<String> ipoFailures) {}

  static void migrateEarningsMetadata(){
    try{
      synchronized(LOCK){
        var events=readEvents(); boolean changed=false;
        for(int i=0;i<events.size();i++){
          Event e=events.get(i);
          if(!"EARNINGS".equalsIgnoreCase(e.type))continue;
          String title=e.title==null?"":e.title;
          String sym=e.scope==null?"":e.scope.toUpperCase(Locale.ROOT);
          ConfirmedEarnings ce=CONFIRMED_EARNINGS.get(sym);

          if(ce!=null){
            String when=String.format(Locale.US,"%s %s",ce.date,
              ce.timing.equals("AMC")?"16:30":ce.timing.equals("BMO")?"08:00":"12:00");
            String nt="CONFIRMED earnings • "+sym+" • timing="+ce.timing+" • source="+ce.source+" • auto-synced";
            String impact="LOW";
            if(!when.equals(e.when)||!nt.equals(e.title)||!impact.equals(e.impact)){
              events.set(i,new Event(e.id,when,"EARNINGS",impact,sym,nt,e.created)); changed=true;
            }
          }else if(!title.startsWith("CONFIRMED earnings")&&!title.startsWith("CORROBORATED earnings")&&!title.startsWith("ESTIMATED earnings")){
            String src=title.contains("Xoomar")?"Xoomar/SEC":title.contains("Alpha Vantage")?"Alpha Vantage":"Legacy calendar";
            String nt="ESTIMATED earnings • "+sym+" • timing=TBD • source="+src+" • auto-synced";
            events.set(i,new Event(e.id,e.when,"EARNINGS","LOW",sym,nt,e.created)); changed=true;
          }
        }
        if(changed)writeEvents(events);
      }
    }catch(Exception e){System.err.println("Earnings metadata migration: "+e.getMessage());}
  }

  static void startCorporateCalendarWorker(){
    migrateEarningsMetadata();
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
  static void outcomeStatusEndpoint(HttpExchange x)throws Exception{
    long total=0,m15=0,m30=0,m60=0,mfe=0,mae=0;
    String storage="local/unknown", note="";
    try{
      if(!DATABASE_URL.isBlank()){
        storage="PostgreSQL";
        try(Connection c=db(); Statement st=c.createStatement();
            ResultSet r=st.executeQuery("""
              SELECT COUNT(*) total,
                     COUNT(return_15m) m15,
                     COUNT(return_30m) m30,
                     COUNT(return_60m) m60,
                     COUNT(max_gain_60m) mfe,
                     COUNT(max_drawdown_60m) mae
              FROM signal_outcomes
            """)){
          if(r.next()){total=r.getLong("total");m15=r.getLong("m15");m30=r.getLong("m30");m60=r.getLong("m60");mfe=r.getLong("mfe");mae=r.getLong("mae");}
        }
      }else note="PostgreSQL not active; cloud outcome validation requires the persistent database.";
    }catch(Exception e){note="Outcome status query failed: "+e.getMessage();}
    String state=(m60>0&&mfe>0&&mae>0)?"MEASURING":(total>0?"WAITING_FOR_MATURE_SIGNALS":"NO_SIGNALS");
    json(x,200,"{\"state\":\""+esc(state)+"\",\"storage\":\""+esc(storage)+"\",\"total\":"+total+
      ",\"measured15\":"+m15+",\"measured30\":"+m30+",\"measured60\":"+m60+
      ",\"measuredMfe\":"+mfe+",\"measuredMae\":"+mae+",\"note\":\""+esc(note)+"\"}");
  }

  static void earningsVerificationEndpoint(HttpExchange x)throws Exception{
    StringBuilder b=new StringBuilder("[");boolean first=true;
    for(var v:LAST_EARNINGS_VERIFICATION.values()){
      if(!first)b.append(',');first=false;
      b.append("{\"symbol\":\"").append(esc(v.symbol)).append("\",\"status\":\"").append(esc(v.status))
       .append("\",\"date\":\"").append(v.selectedDate==null?"":v.selectedDate).append("\",\"timing\":\"").append(esc(v.timing))
       .append("\",\"source\":\"").append(esc(v.source)).append("\",\"note\":\"").append(esc(v.note)).append("\"}");
    }
    b.append(']');json(x,200,b.toString());
  }

  static void earningsMetaEndpoint(HttpExchange x)throws Exception{
    StringBuilder b=new StringBuilder("["); boolean first=true;
    for(var e:readEvents()){
      if(!"EARNINGS".equalsIgnoreCase(e.type))continue;
      String title=e.title==null?"":e.title;
      String confidence=title.startsWith("CONFIRMED earnings")?"CONFIRMED":title.startsWith("CORROBORATED earnings")?"CORROBORATED":title.startsWith("ESTIMATED earnings")?"ESTIMATED":"CACHED";
      String timing="TBD",source="";
      var tm=java.util.regex.Pattern.compile("timing=([^•]+)").matcher(title); if(tm.find())timing=tm.group(1).trim();
      var sm=java.util.regex.Pattern.compile("source=([^•]+)").matcher(title); if(sm.find())source=sm.group(1).trim();
      if(!first)b.append(',');first=false;
      b.append("{\"symbol\":\"").append(esc(e.scope)).append("\",\"confidence\":\"").append(esc(confidence))
       .append("\",\"timing\":\"").append(esc(timing)).append("\",\"source\":\"").append(esc(source))
       .append("\",\"impact\":\"").append(esc(e.impact)).append("\",\"title\":\"").append(esc(title)).append("\"}");
    }
    b.append(']');json(x,200,b.toString());
  }

  static void corporateDiagnosticsEndpoint(HttpExchange x)throws Exception{
    json(x,200,"{\"earnings\":"+diagJson(LAST_EARNINGS_DIAG)+",\"ipos\":"+diagJson(LAST_IPO_DIAG)+
      ",\"providers\":{\"xoomar\":"+diagJson(LAST_XOOMAR_DIAG)+",\"alphaEarnings\":"+diagJson(LAST_AV_EARNINGS_DIAG)+
      ",\"nasdaqIpo\":"+diagJson(LAST_NASDAQ_IPO_DIAG)+",\"alphaIpo\":"+diagJson(LAST_AV_IPO_DIAG)+"}}");
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
    try{
      var nf=fetchNfinIpos();
      var haveN=new HashSet<String>();for(var h:ipos)haveN.add((h.symbol+"|"+h.date).toLowerCase(Locale.ROOT));
      for(var h:nf)if(haveN.add((h.symbol+"|"+h.date).toLowerCase(Locale.ROOT)))ipos.add(h);
    }catch(Exception e){ipoFailures.add("nfin IPO merge: "+e.getMessage());}
    String key=System.getenv("ALPHA_VANTAGE_API_KEY");
    if(key!=null&&!key.isBlank()){
      try{
        var av=fetchAlphaVantageIpos(key);
        var have=new HashSet<String>();for(var h:ipos)have.add((h.symbol+"|"+h.date).toLowerCase(Locale.ROOT));
        for(var h:av)if(have.add((h.symbol+"|"+h.date).toLowerCase(Locale.ROOT)))ipos.add(h);
      }catch(Exception e){ipoFailures.add("Alpha Vantage IPO merge: "+e.getMessage());}
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
    if(di<0){LAST_IPO_DIAG=LAST_AV_IPO_DIAG=new ProviderDiag("Alpha Vantage",r.statusCode(),ct,header,Math.max(0,lines.length-1),0,0,"missing date column");throw new IOException("CSV missing IPO date column; header="+header);}
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
    LAST_IPO_DIAG=LAST_AV_IPO_DIAG=new ProviderDiag("Alpha Vantage",r.statusCode(),ct,header,Math.max(0,lines.length-1),parsed,out.size(),"valid CSV");
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
    boolean anyProviderSucceeded=false;

    try{
      hits.addAll(fetchXoomarEarnings(stocks));
      primary="Xoomar/SEC"; anyProviderSucceeded=true;
    }catch(Exception e){failures.add("Xoomar: "+e.getMessage());}

    String avKey=System.getenv("ALPHA_VANTAGE_API_KEY");
    if(avKey!=null&&!avKey.isBlank()){
      try{
        var avHits=fetchAlphaVantageCalendar(avKey,stocks);
        // Merge without replacing Xoomar dates; Xoomar/SEC remains preferred when both have the ticker.
        var have=new HashSet<String>();for(var h:hits)have.add(h.symbol.toUpperCase(Locale.ROOT));
        for(var h:avHits)if(have.add(h.symbol.toUpperCase(Locale.ROOT)))hits.add(h);
        anyProviderSucceeded=true;
      }catch(Exception e){failures.add("Alpha Vantage merge: "+e.getMessage());}
    }

    if(!anyProviderSucceeded){
      try{
        YahooSession ys=openYahooSession();
        for(var st:stocks){
          try{var h=fetchYahooEarnings(ys,st.symbol);if(h!=null)hits.add(h);}
          catch(Exception e){failures.add("Yahoo "+st.symbol+": "+e.getMessage());}
        }
        if(!hits.isEmpty())primary="Yahoo fallback";
      }catch(Exception e){failures.add("Yahoo fallback: "+e.getMessage());}
    }
 
    {
      hits=new ArrayList<>(applyVerification(hits));
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
    migrateEarningsMetadata();
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

  static volatile long XOOMAR_BACKOFF_UNTIL=0L;
  static List<EarningsHit> fetchXoomarEarnings(List<Stock> stocks)throws Exception{
    long now=System.currentTimeMillis();
    if(now<XOOMAR_BACKOFF_UNTIL){
      long mins=Math.max(1,(XOOMAR_BACKOFF_UNTIL-now)/60000);
      LAST_EARNINGS_DIAG=LAST_XOOMAR_DIAG=new ProviderDiag("Xoomar/SEC per-ticker",0,"","",0,0,0,"circuit open; retry in ~"+mins+"m");
      return new ArrayList<>();
    }
    HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();
    var out=new ArrayList<EarningsHit>(); int providerRows=0,parsed=0,ok=0,notFound=0,failed=0,consecutiveFailures=0;
    ZoneId ny=ZoneId.of("America/New_York"); LocalDate today=LocalDate.now(ny),limit=today.plusDays(120);
    for(var st:stocks){
      if(consecutiveFailures>=3){
        XOOMAR_BACKOFF_UNTIL=System.currentTimeMillis()+30*60*1000L;
        break;
      }
      String sym=st.symbol.toUpperCase(Locale.ROOT);
      String u="https://xoomar.com/api/markets/earnings/"+URLEncoder.encode(sym,StandardCharsets.UTF_8);
      try{
        HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(12))
          .header("User-Agent","MarketLedger/3.5").header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()==404){notFound++;consecutiveFailures=0;continue;}
        if(r.statusCode()!=200){failed++;consecutiveFailures++;continue;}
        ok++;consecutiveFailures=0;String body=r.body()==null?"":r.body();
        int ni=body.indexOf("\"next\"");if(ni<0)continue;
        String tail=body.substring(ni,Math.min(body.length(),ni+1800));
        String date=jsonString(tail,"date"),status=jsonString(tail,"status");
        if(date.isBlank())continue;
        providerRows++;
        try{
          LocalDate d=LocalDate.parse(date.substring(0,10));parsed++;
          if(d.isBefore(today)||d.isAfter(limit))continue;
          long epoch=d.atTime(12,0).atZone(ny).toEpochSecond();
          out.add(new EarningsHit(sym,epoch,!status.equalsIgnoreCase("reported"),"Xoomar/SEC"));
        }catch(Exception ignored){}
      }catch(Exception e){failed++;consecutiveFailures++;}
    }
    String note="ticker requests ok="+ok+", 404="+notFound+", failed="+failed;
    if(XOOMAR_BACKOFF_UNTIL>System.currentTimeMillis())note+="; circuit opened 30m after 3 consecutive failures";
    LAST_EARNINGS_DIAG=LAST_XOOMAR_DIAG=new ProviderDiag("Xoomar/SEC per-ticker",ok>0?200:0,"application/json",
      "data.next: date,status,basis",providerRows,parsed,out.size(),note);
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
        .header("Accept","application/json, text/plain, */*").header("Accept-Language","en-US,en;q=0.9")
        .header("Referer","https://www.nasdaq.com/market-activity/ipos").GET().build(),HttpResponse.BodyHandlers.ofString());
      http=r.statusCode();ct=r.headers().firstValue("content-type").orElse(ct);
      if(http!=200)throw new IOException("HTTP "+http+" for "+month);
      String body=r.body()==null?"":r.body();

      // Nasdaq groups the calendar into priced/upcoming/filings/withdrawn.
      // Restrict parsing to the upcoming section so metadata objects are not mistaken for IPO rows.
      int up=body.indexOf("\"upcoming\"");
      if(up<0)continue;
      int next=body.length();
      for(String section:new String[]{"\"priced\"","\"filings\"","\"withdrawn\""}) {
        int p=body.indexOf(section,up+10); if(p>up&&p<next)next=p;
      }
      String upcoming=body.substring(up,next);
      for(String o:jsonObjects(upcoming)){
        String sym=jsonString(o,"proposedTickerSymbol");
        String name=jsonString(o,"companyName");
        String date=jsonString(o,"expectedPriceDate");
        if(date.isBlank())date=jsonString(o,"expectedDate");
        if(date.isBlank()||name.isBlank())continue;
        rows++;
        try{
          LocalDate d;
          try{d=LocalDate.parse(date.substring(0,10));}
          catch(Exception x){d=LocalDate.parse(date,java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy"));}
          String price=jsonString(o,"proposedSharePrice");
          if(price.isBlank()){
            String lo=jsonString(o,"priceRangeLow"),hi=jsonString(o,"priceRangeHigh");
            if(!lo.isBlank()&&!hi.isBlank())price="$"+lo+"–$"+hi;
          }
          parsed++; all.add(new IpoHit(sym,name,d,price,"Nasdaq IPO Calendar"));
        }catch(Exception ignored){}
      }
    }
    var seen=new HashSet<String>();var out=new ArrayList<IpoHit>();
    for(var h:all)if(seen.add((h.symbol+"|"+h.name+"|"+h.date).toLowerCase(Locale.ROOT)))out.add(h);
    LAST_IPO_DIAG=LAST_NASDAQ_IPO_DIAG=new ProviderDiag("Nasdaq upcoming",http,ct,
      "data.upcoming.rows: proposedTickerSymbol,companyName,expectedPriceDate,proposedSharePrice",
      rows,parsed,out.size(),"parsed upcoming section only");
    return out;
  }

  static List<IpoHit> fetchNfinIpos()throws Exception{
    String u="https://nfin.dev/api/v1/ipo/calendar";
    HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(8)).build();
    HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(20))
      .header("User-Agent","MarketLedger/3.4").header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
    if(r.statusCode()!=200)throw new IOException("HTTP "+r.statusCode());
    String body=r.body()==null?"":r.body(); var out=new ArrayList<IpoHit>();
    LocalDate today=LocalDate.now(ZoneId.of("America/New_York"));
    for(String o:jsonObjects(body)){
      String sym=jsonString(o,"symbol"); if(sym.isBlank())sym=jsonString(o,"proposedTickerSymbol");
      String name=jsonString(o,"name"); if(name.isBlank())name=jsonString(o,"companyName");
      String date=jsonString(o,"date"); if(date.isBlank())date=jsonString(o,"expectedPriceDate");
      if(name.isBlank()||date.isBlank())continue;
      try{
        LocalDate d;
        try{d=LocalDate.parse(date.substring(0,10));}
        catch(Exception e){d=LocalDate.parse(date,java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy"));}
        if(d.isBefore(today.minusDays(2)))continue;
        String price=jsonString(o,"priceRange"); if(price.isBlank())price=jsonString(o,"proposedSharePrice");
        out.add(new IpoHit(sym,name,d,price,"nfin/Nasdaq"));
      }catch(Exception ignored){}
    }
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
    if(si<0||di<0){LAST_EARNINGS_DIAG=LAST_AV_EARNINGS_DIAG=new ProviderDiag("Alpha Vantage",r.statusCode(),ct,header,Math.max(0,lines.length-1),0,0,"missing columns");throw new IOException("CSV missing symbol/reportDate; header="+header);}
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
    LAST_EARNINGS_DIAG=LAST_AV_EARNINGS_DIAG=new ProviderDiag("Alpha Vantage",r.statusCode(),ct,header,Math.max(0,lines.length-1),parsed,out.size(),"valid CSV");
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
      if(p.equals("/api/stocks/v5o-universe") && m.equals("POST")) { syncV5OUniverse(x); return; }
      if(p.equals("/api/stocks") && m.equals("POST")) { addStock(x); return; }
      if(p.startsWith("/api/stocks/") && p.endsWith("/price") && m.equals("POST")) { setPrice(x,p.split("/")[3]); return; }
      if(p.startsWith("/api/stocks/") && m.equals("DELETE")) { deleteStock(x,p.substring("/api/stocks/".length())); return; }
      if(p.equals("/api/calls") && m.equals("POST")) { addCall(x); return; }
      if(p.matches("/api/calls/\\d+/resolve") && m.equals("POST")) { resolveCall(x,Long.parseLong(p.split("/")[3])); return; }
      if(p.matches("/api/calls/\\d+/void") && m.equals("POST")) { voidCall(x,Long.parseLong(p.split("/")[3])); return; }
      if(p.equals("/api/notes") && m.equals("POST")) { addNote(x); return; }
      if(p.equals("/api/events") && m.equals("POST")) { addEvent(x); return; }
      if(p.equals("/api/context/schwab/sync") && m.equals("POST")) { syncSchwab(x); return; }
      if(p.equals("/api/news/catalysts") && m.equals("GET")) { newsCatalysts(x); return; }
      if(p.equals("/api/context/earnings/sync") && m.equals("POST")) { syncEarningsEndpoint(x); return; }
      if(p.equals("/api/context/corporate/sync") && m.equals("POST")) { syncCorporateEndpoint(x); return; }
      if(p.equals("/api/context/corporate/diagnostics") && m.equals("GET")) { corporateDiagnosticsEndpoint(x); return; }
      if(p.equals("/api/context/earnings/meta") && m.equals("GET")) { earningsMetaEndpoint(x); return; }
      if(p.equals("/api/context/earnings/verification") && m.equals("GET")) { earningsVerificationEndpoint(x); return; }
      if(p.equals("/api/outcomes/status") && m.equals("GET")) { outcomeStatusEndpoint(x); return; }
      if(p.equals("/api/settings/marketdata") && m.equals("GET")) { marketSettingsGet(x); return; }
      if(p.equals("/api/settings/marketdata") && m.equals("POST")) { marketSettingsSave(x); return; }
      if(p.startsWith("/api/microstructure/") && m.equals("GET")) { microstructure(x,p.substring("/api/microstructure/".length())); return; }
      if(p.startsWith("/api/overnight-bars/") && m.equals("GET")) { overnightBars(x,p.substring("/api/overnight-bars/".length())); return; }
      if(p.startsWith("/api/session-bars/") && m.equals("GET")) { sessionBars(x,p.substring("/api/session-bars/".length())); return; }
      if(p.startsWith("/api/feed-diagnostics/") && m.equals("GET")) { feedDiagnostics(x,p.substring("/api/feed-diagnostics/".length())); return; }
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

  static int ensureV5OMonitoringUniverse() throws IOException {
    synchronized(LOCK){
      var stocks=readStocks(); var have=new HashSet<String>();
      for(var z:stocks)have.add(z.symbol.toUpperCase(Locale.ROOT));
      int added=0;
      for(var item:V5O_MONITORING_UNIVERSE){String sym=item[0];if(have.add(sym)){stocks.add(new Stock(sym,item[1],0,0,now()));added++;}}
      if(added>0)writeStocks(stocks);
      return added;
    }
  }
  static void syncV5OUniverse(HttpExchange x)throws Exception{
    int before=readStocks().size(), added=ensureV5OMonitoringUniverse(), after=readStocks().size();
    json(x,200,"{\"ok\":true,\"added\":"+added+",\"before\":"+before+",\"after\":"+after+"}");
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
  static void newsCatalysts(HttpExchange x)throws Exception{
    Map<String,String> qv=query(x.getRequestURI().getRawQuery());
    String raw=qv.getOrDefault("symbols","").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.,-]","");
    LinkedHashSet<String> syms=new LinkedHashSet<>();
    for(String z:raw.split(",")) if(z.matches("[A-Z][A-Z0-9.-]{0,9}")) syms.add(z);
    if(syms.isEmpty()) for(Stock s:readStocks()) syms.add(s.symbol());
    if(syms.size()>64) syms=new LinkedHashSet<>(new ArrayList<>(syms).subList(0,64));
    String key=String.join(",",syms);
    long now=System.currentTimeMillis();
    boolean force="1".equals(qv.getOrDefault("refresh","0"));
    if(!force && key.equals(NEWS_CACHE_KEY) && now-NEWS_CACHE_AT<300000L){json(x,200,NEWS_CACHE_JSON);return;}
    List<String> list=new ArrayList<>(syms); List<NewsItem> items=new ArrayList<>();
    for(int start=0;start<list.size();start+=8){
      List<String> batch=list.subList(start,Math.min(start+8,list.size()));
      List<String> terms=new ArrayList<>();
      for(String symbol:batch){String alias=newsAlias(symbol);terms.add("\""+(alias.isBlank()?symbol:alias)+"\"");}
      String expr=String.join(" OR ",terms)+" when:1d";
      String url="https://news.google.com/rss/search?q="+URLEncoder.encode(expr,StandardCharsets.UTF_8)+"&hl=en-US&gl=US&ceid=US:en";
      try{
        HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(7)).build();
        HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).header("User-Agent","Mozilla/5.0 MarketLedger/5M").GET().build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()==200) items.addAll(parseNewsRss(r.body(),syms));
      }catch(Exception e){System.err.println("News catalyst warning: "+e.getMessage());}
    }
    // V5M.8.2: second-pass source-focused discovery. Google News remains the public index;
    // this pass explicitly asks for high-value financial publishers (including CNBC and TheStreet)
    // so a catalyst is less likely to be missed merely because the generic company query ranked it low.
    // We do not scrape paywalled/proprietary pages and all returned headlines still pass the same
    // entity-resolution, ownership, novelty, materiality and deduplication guards below.
    items.addAll(fetchSourceFocusedNews(syms));

    // V5M.8: scan market-wide crypto catalysts separately from company-name searches.
    // These items are explicitly THEME context: they can support exposed equities but never
    // masquerade as a company-specific catalyst or create a BUY/STRONG signal by themselves.
    items.addAll(fetchCryptoThemeNews(syms));
    LinkedHashMap<String,NewsItem> uniq=new LinkedHashMap<>();
    items.stream().sorted(Comparator.comparingInt((NewsItem n)->n.catalystScore()).reversed().thenComparing(Comparator.comparingInt((NewsItem n)->n.sourceScore()+n.relevanceScore()).reversed())).forEach(n->uniq.putIfAbsent(newsDedupeKey(n.title()),n));
    items=clusterNewsEvents(new ArrayList<>(uniq.values()));
    items.sort(Comparator.comparingInt((NewsItem n)->n.catalystScore()).reversed().thenComparing(Comparator.comparingInt((NewsItem n)->n.sourceCount()).reversed()));
    if(items.size()>24)items=items.subList(0,24);
    Map<String,Integer> themes=new LinkedHashMap<>(); for(NewsItem n:items)themes.merge(n.theme(),1,Integer::sum);
    Map<String,String> states=new LinkedHashMap<>(); for(String sym:syms)states.put(sym,"NO_FRESH_CATALYST");
    for(NewsItem n:items) if(!n.directness().equals("THEME")) for(String sym:n.symbols()){String cur=states.getOrDefault(sym,"NO_FRESH_CATALYST");String nx=n.catalystClass();if(newsStateRank(nx)>newsStateRank(cur))states.put(sym,nx);}
    long actionable=states.values().stream().filter(v->v.equals("ACTIONABLE_CATALYST")).count(), supporting=states.values().stream().filter(v->v.equals("SUPPORTING_CONTEXT")).count(), background=states.values().stream().filter(v->v.equals("BACKGROUND")).count(), none=states.values().stream().filter(v->v.equals("NO_FRESH_CATALYST")).count();
    StringBuilder b=new StringBuilder("{\"updated\":").append(q(Instant.now().toString())).append(",\"mode\":\"FULL_UNIVERSE_PUBLIC_WEB\",\"notice\":\"Public headline intelligence; Reuters/LSEG professional feeds require separate entitlement. News is context, not a trading instruction.\",\"coverage\":{\"scanned\":").append(syms.size()).append(",\"actionable\":").append(actionable).append(",\"supporting\":").append(supporting).append(",\"background\":").append(background).append(",\"noFreshCatalyst\":").append(none).append("},\"states\":[");
    int si=0;for(var e:states.entrySet()){if(si++>0)b.append(',');b.append("{\"symbol\":").append(q(e.getKey())).append(",\"state\":").append(q(e.getValue())).append('}');}
    b.append("],\"themes\":[");
    int ti=0;for(var e:themes.entrySet().stream().sorted((a,z)->Integer.compare(z.getValue(),a.getValue())).limit(6).toList()){if(ti++>0)b.append(',');b.append("{\"name\":").append(q(e.getKey())).append(",\"stories\":").append(e.getValue()).append('}');}
    b.append("],\"items\":[");for(int i=0;i<items.size();i++){if(i>0)b.append(',');NewsItem n=items.get(i);b.append("{\"title\":").append(q(n.title())).append(",\"link\":").append(q(n.link())).append(",\"source\":").append(q(n.source())).append(",\"published\":").append(q(n.published())).append(",\"theme\":").append(q(n.theme())).append(",\"sourceScore\":").append(n.sourceScore()).append(",\"relevanceScore\":").append(n.relevanceScore()).append(",\"catalystScore\":").append(n.catalystScore()).append(",\"catalystClass\":").append(q(n.catalystClass())).append(",\"catalystReason\":").append(q(n.catalystReason())).append(",\"novelty\":").append(q(n.novelty())).append(",\"provenance\":").append(q(n.provenance())).append(",\"evidence\":").append(q(n.evidence())).append(",\"materiality\":").append(q(n.materiality())).append(",\"directness\":").append(q(n.directness())).append(",\"sourceCount\":").append(n.sourceCount()).append(",\"sources\":[");for(int k=0;k<n.sources().size();k++){if(k>0)b.append(',');b.append(q(n.sources().get(k)));}b.append("],\"symbols\":[");for(int j=0;j<n.symbols().size();j++){if(j>0)b.append(',');b.append(q(n.symbols().get(j)));}b.append("]}");}b.append("]}");
    NEWS_CACHE_KEY=key;NEWS_CACHE_AT=now;NEWS_CACHE_JSON=b.toString();json(x,200,NEWS_CACHE_JSON);
  }
  static List<NewsItem> fetchSourceFocusedNews(Set<String> syms){
    List<NewsItem> out=new ArrayList<>();
    try{
      List<String> list=new ArrayList<>(syms);
      HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(7)).build();
      // Keep the source list intentionally editorial rather than using it as a bullish-quality signal.
      // Source score affects evidence quality only; it cannot bypass materiality/tape/risk gates.
      String publishers="(source:Reuters OR source:CNBC OR source:TheStreet OR source:\"Yahoo Finance\" OR source:Barrons OR source:Benzinga OR source:MarketWatch)";
      for(int start=0;start<list.size();start+=8){
        List<String> batch=list.subList(start,Math.min(start+8,list.size()));
        List<String> terms=new ArrayList<>();
        for(String symbol:batch){String alias=newsAlias(symbol);terms.add("\""+(alias.isBlank()?symbol:alias)+"\"");}
        String expr="("+String.join(" OR ",terms)+") "+publishers+" when:1d";
        String url="https://news.google.com/rss/search?q="+URLEncoder.encode(expr,StandardCharsets.UTF_8)+"&hl=en-US&gl=US&ceid=US:en";
        try{
          HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).header("User-Agent","Mozilla/5.0 MarketLedger/5M8.2").GET().build(),HttpResponse.BodyHandlers.ofString());
          if(r.statusCode()==200)out.addAll(parseNewsRss(r.body(),syms));
        }catch(Exception e){System.err.println("Source-focused news warning: "+e.getMessage());}
      }
    }catch(Exception e){System.err.println("Source-focused discovery warning: "+e.getMessage());}
    return out;
  }

  static List<NewsItem> fetchCryptoThemeNews(Set<String> requested){
    List<NewsItem> out=new ArrayList<>();
    if(requested.stream().noneMatch(s->Set.of("COIN","MSTR","CRCL").contains(s)))return out;
    try{
      String expr="(Bitcoin OR cryptocurrency OR stablecoin OR USDC OR crypto ETF OR digital assets) when:1d";
      String url="https://news.google.com/rss/search?q="+URLEncoder.encode(expr,StandardCharsets.UTF_8)+"&hl=en-US&gl=US&ceid=US:en";
      HttpClient c=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(7)).build();
      HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).header("User-Agent","Mozilla/5.0 MarketLedger/5M8").GET().build(),HttpResponse.BodyHandlers.ofString());
      if(r.statusCode()!=200)return out;
      var im=java.util.regex.Pattern.compile("(?is)<item>(.*?)</item>").matcher(r.body());
      while(im.find()&&out.size()<18){
        String z=im.group(1),title=xmlDecode(xmlTag(z,"title").replaceAll("(?is)<[^>]+>"," ")).replaceAll("\\s+"," ").trim();
        if(title.isBlank())continue;
        String low=title.toLowerCase(Locale.ROOT),link=xmlTag(z,"link"),pub=xmlTag(z,"pubDate"),source=xmlDecode(xmlTag(z,"source"));
        if(!(low.matches(".*(bitcoin|crypto|cryptocurrency|stablecoin|usdc|digital asset|spot etf|crypto etf).*")))continue;
        // Reject generic price-prediction/listicle content. Theme propagation is event/context only.
        if(low.matches(".*(price prediction|forecast|should you buy|best crypto|top .* crypto|could reach|will .* hit|technical analysis).*"))continue;
        LinkedHashSet<String> hits=new LinkedHashSet<>();
        boolean stable=low.matches(".*(stablecoin|usdc|circle).*"), exchange=low.matches(".*(coinbase|crypto exchange|exchange regulation|tokenized market).*"), btc=low.matches(".*(bitcoin|btc|spot etf|crypto etf).*"), reg=low.matches(".*(sec |regulat|legislation|lawmakers|treasury|fed |approval|ban|rules).*" );
        if(requested.contains("CRCL")&&(stable||reg))hits.add("CRCL");
        if(requested.contains("COIN")&&(exchange||btc||stable||reg))hits.add("COIN");
        if(requested.contains("MSTR")&&btc)hits.add("MSTR");
        if(hits.isEmpty())continue;
        int src=sourceScore(source), relevance=80;
        String novelty=applyNewsFreshness(newsNovelty(low),pub); if(novelty.equals("REJECTED"))continue;
        String evidence=newsEvidence(low,source), materiality=(reg||stable||low.matches(".*(etf flow|inflow|outflow|approval|legislation).*"))?"MEDIUM":"LOW";
        int score=catalystScore(low,src,relevance,pub,novelty,evidence,materiality,"INDIRECT");
        // Theme context is deliberately capped. It can inform evidence fusion but cannot become an actionable corporate catalyst.
        score=Math.min(68,Math.max(38,score));
        String cls=score>=52&&materiality.equals("MEDIUM")?"SUPPORTING_CONTEXT":"BACKGROUND";
        String reason="crypto-theme context • "+evidence.toLowerCase(Locale.ROOT)+" evidence • "+materiality.toLowerCase(Locale.ROOT)+" materiality • propagated exposure, not company-specific news";
        String srcName=source.isBlank()?"Public news":source;
        out.add(new NewsItem(title,link,srcName,pub,new ArrayList<>(hits),"Crypto / Digital Assets",src,relevance,score,cls,reason,novelty,"THEME CONTEXT",evidence,materiality,"THEME",1,List.of(srcName)));
      }
    }catch(Exception e){System.err.println("Crypto theme news warning: "+e.getMessage());}
    return out;
  }

  static List<NewsItem> parseNewsRss(String xml,Set<String> syms){
    List<NewsItem> out=new ArrayList<>(); var ip=java.util.regex.Pattern.compile("(?is)<item>(.*?)</item>"); var im=ip.matcher(xml);
    while(im.find()){String z=im.group(1),title=xmlTag(z,"title"),link=xmlTag(z,"link"),pub=xmlTag(z,"pubDate"),source=xmlTag(z,"source"); if(title.isBlank())continue;
      title=xmlDecode(title.replaceAll("(?is)<[^>]+>"," ")).replaceAll("\\s+"," ").trim(); source=xmlDecode(source.replaceAll("(?is)<[^>]+>"," ")).trim();
      String upper=title.toUpperCase(Locale.ROOT), low=title.toLowerCase(Locale.ROOT);
      if(newsJunk(low))continue;
      List<String> hits=new ArrayList<>(); int relevance=0;
      for(String symbol:syms){int r=newsSymbolRelevance(symbol,upper);if(r>0){hits.add(symbol);relevance=Math.max(relevance,r);}}
      if(hits.isEmpty()||relevance<70)continue;
      hits=filterSubjectOwnership(low,hits); if(hits.isEmpty())continue;
      String novelty=applyNewsFreshness(newsNovelty(low),pub), provenance=newsProvenance(low);
      if(novelty.equals("REJECTED"))continue;
      String evidence=newsEvidence(low,source), directness=newsDirectness(low,hits,evidence);
      String materiality=newsMateriality(low,novelty,evidence,directness);
      String theme=newsTheme(low);int score=sourceScore(source);int catalyst=catalystScore(low,score,relevance,pub,novelty,evidence,materiality,directness);String cclass=catalystClass(catalyst,novelty,evidence,materiality);String creason=catalystReason(low,catalyst,novelty,evidence,materiality,directness);if(cclass.equals("IGNORE"))continue;String src=source.isBlank()?"Public news":source;out.add(new NewsItem(title,link,src,pub,hits,theme,score,relevance,catalyst,cclass,creason,novelty,provenance,evidence,materiality,directness,1,List.of(src)));
    }return out;
  }
  static int newsSymbolRelevance(String symbol,String upper){
    String alias=newsAlias(symbol);
    // V5M.8.1 entity-resolution guards: company aliases must not match unrelated places/events/common words.
    // ETN/Eaton is collision-prone (Eaton County, Eaton Fire, people/places named Eaton). Require issuer or ticker context.
    if(symbol.equals("ETN")){
      boolean issuer=containsPhrase(upper,"EATON CORPORATION")||containsPhrase(upper,"EATON CORP")||containsPhrase(upper,"EATON CORP.");
      boolean etnCtx=java.util.regex.Pattern.compile("(?:NYSE\\s*[:(]?\\s*ETN|\\(ETN\\)|ETN\\s+(?:STOCK|SHARES|EARNINGS|REVENUE|GUIDANCE))").matcher(upper).find();
      return issuer?100:(etnCtx?92:0);
    }
    // COHR is especially collision-prone: ordinary adjective "coherent" is not Coherent Corp.
    if(symbol.equals("COHR")){
      if(containsPhrase(upper,"COHERENT CORP")||containsPhrase(upper,"COHERENT CORP.")||containsPhrase(upper,"COHERENT CORPORATION"))return 100;
      boolean cohrCtx=java.util.regex.Pattern.compile("(?:NASDAQ\\s*[:(]?\\s*COHR|\\(COHR\\)|COHR\\s+(?:STOCK|SHARES|EARNINGS))").matcher(upper).find();
      return cohrCtx?92:0;
    }
    // V5M.8.2.3 strict identity guard for common-word / collision-prone tickers.
    // A bare ticker token is not enough (for example NET in "net sales", NOW in ordinary prose, or ON as a preposition).
    if(Set.of("NET","NOW","ON","IT","ALL","AI").contains(symbol)){
      String issuer=switch(symbol){
        case "NET"->"CLOUDFLARE"; case "NOW"->"SERVICENOW"; case "ON"->"ONSEMI";
        case "IT"->"GARTNER"; case "ALL"->"ALLSTATE"; case "AI"->"C3.AI"; default->"";
      };
      boolean issuerCtx=!issuer.isBlank() && containsPhrase(upper,issuer);
      if(symbol.equals("ON") && (containsPhrase(upper,"ON SEMICONDUCTOR")||containsPhrase(upper,"ON SEMICONDUCTOR CORPORATION")))issuerCtx=true;
      if(symbol.equals("AI") && (containsPhrase(upper,"C3 AI")||containsPhrase(upper,"C3.AI, INC")||containsPhrase(upper,"C3.AI INC")))issuerCtx=true;
      boolean explicitTicker=java.util.regex.Pattern.compile("(?:NASDAQ|NYSE)\\s*[:(]?\\s*"+java.util.regex.Pattern.quote(symbol)+"(?:\\)|\\s+(?:STOCK|SHARES|EARNINGS|REVENUE|GUIDANCE)|(?:[^A-Z0-9]|$))").matcher(upper).find()
        || java.util.regex.Pattern.compile("\\("+java.util.regex.Pattern.quote(symbol)+"\\)").matcher(upper).find();
      return issuerCtx?100:(explicitTicker?92:0);
    }
    // AAPL needs issuer context because bare "Apple" also appears in awards, food, agriculture, schools, etc.
    if(symbol.equals("AAPL") && containsPhrase(upper,"APPLE")){
      String l=upper.toLowerCase(Locale.ROOT);
      boolean issuer=l.matches(".*(apple inc|aapl|iphone|ipad|macbook|\\bmac\\b|ios|siri|apple watch|vision pro|app store|tim cook|cupertino|stock|shares|earnings|revenue|guidance|analyst|price target|nasdaq|investor|settlement|antitrust|developer).*" );
      return issuer?100:0;
    }
    if(!alias.isBlank() && containsPhrase(upper,alias)) return 100;
    // Short/common ticker strings are never accepted by themselves. This prevents BE="be", COIN=literal coin, ARM=body part, META=generic prefix, etc.
    if(Set.of("BE","COIN","ARM","META","AI","ON","IT","ALL","NOW").contains(symbol)) return 0;
    boolean ticker=java.util.regex.Pattern.compile("(?:^|[^A-Z0-9])(?:NASDAQ|NYSE|NYSEARCA)?\\s*[:(]?\\s*"+java.util.regex.Pattern.quote(symbol)+"\\s*[)]?(?:[^A-Z0-9]|$)").matcher(upper).find();
    if(!ticker)return 0;
    // Unambiguous 4+ character tickers can match, but require market/company context to avoid incidental acronyms.
    String l=upper.toLowerCase(Locale.ROOT); boolean market=l.matches(".*(stock|shares|earnings|revenue|guidance|analyst|price target|nasdaq|nyse|investor|semiconductor|chip|data center|acquisition|partnership|contract|ipo|dividend|market cap).*" );
    return market?78:0;
  }
  static List<String> filterSubjectOwnership(String low,List<String> hits){
    List<String> out=new ArrayList<>(hits);
    // Headline subject ownership: references inside another company/IPOs story are exposure, not automatically a direct event.
    if(out.contains("MSFT") && low.matches(".*(nscale|nscl).*ipo.*") && !low.matches(".*microsoft\\s+(announces|announced|signs|signed|launches|wins|acquires).*")) out.remove("MSFT");
    // Retrospective performance content is never an issuer event even when the company name is exact.
    if(low.matches(".*(if you invested|invested .* years ago|worth today|is now worth|since chatgpt launched|historical return|past .* years).*")) out.clear();
    return out;
  }

  static boolean containsPhrase(String upper,String phrase){return java.util.regex.Pattern.compile("(?:^|[^A-Z0-9])"+java.util.regex.Pattern.quote(phrase)+"(?:[^A-Z0-9]|$)").matcher(upper).find();}

  static String newsNovelty(String low){
    if(low.matches(".*(best .* deals|deals you can shop|coupon|discount|review|how to|technical blog|stock forecast|price prediction|prediction:|where will .* stock|should you buy|is .* a buy|stocks to buy|stock flashes signal|beats stock market upswing|what investors need to know|analyst consensus explained|could be worth|parabolic|if you invested|invested .* years ago|worth today|is now worth|since chatgpt launched|historical return|past .* years).*"))return "REJECTED";
    if(low.matches(".*(soars|surges|jumps|rallies|rally|rose|gains|climbs|falls|slides|drops|after .* rally|after .* surge|why .* stock|what drove|what happened).*"))return "COMMENTARY";
    if(low.matches(".*(today|announces|announced|reports|reported|files|filed|wins|won|signs|signed|launches|launched|unveils|unveiled|raises guidance|cuts guidance|approves|approved|acquires|acquired|merger|partnership|contract|order|settlement|lawsuit|investigation|upgrade|downgrade|price target).*"))return "NEW_CATALYST";
    if(low.matches(".*(earnings|guidance|revenue goal|revenue target|customer|supplier|capacity|shipment|production).*"))return "FOLLOW_THROUGH";
    return "BACKGROUND";
  }
  static String newsProvenance(String low){
    if(low.matches(".*(files|filed|sec |10-k|10-q|8-k|registration statement).*"))return "FILING / PRIMARY EVENT";
    if(low.matches(".*(announces|announced|reports|reported|signs|signed|launches|unveils|wins|contract|partnership|settlement|lawsuit|investigation|upgrade|downgrade|price target).*"))return "REPORTED EVENT";
    if(low.matches(".*(soars|surges|rallies|why .* stock|forecast|prediction|what investors need to know).*"))return "MARKET COMMENTARY";
    return "SECONDARY CONTEXT";
  }
  static String newsEvidence(String low,String source){
    if(low.matches(".*(may|might|could|rumor|rumour|reportedly considering|may be announced|expected to|said to be|possible deal).*"))return "SPECULATIVE";
    if(low.matches(".*(analyst|upgrade|downgrade|price target|rating|initiates coverage|reiterates).*"))return "ANALYST";
    if(low.matches(".*(files|filed|sec |10-k|10-q|8-k|registration statement).*"))return "CONFIRMED";
    return "REPORTED";
  }
  // V5M.8.2.2 Subject Ownership Guard: relevance to an issuer is not the same as issuer ownership of the event.
  // Only issuer-owned corporate events receive DIRECT treatment. Investor holdings, former spinouts,
  // analyst actions and broad market context remain visible but cannot masquerade as corporate catalysts.
  static String newsDirectness(String low,List<String> hits,String evidence){
    if(evidence.equals("ANALYST"))return "ANALYST";
    if(low.matches(".*(spun off from|spinoff from|spinout|spin-out|former .* subsidiary|formerly .* unit|ex-.* subsidiary).*"))return "FORMER_SPINOUT";
    if(low.matches(".*(top portfolio holding|portfolio holding|shares acquired by|shares purchased by|acquires? [0-9,]+ shares of|purchases? [0-9,]+ shares of|buys? [0-9,]+ shares of|adds? [0-9,]+ shares of|increases? (its )?(stake|position|holdings)|decreases? (its )?(stake|position|holdings)|cuts? (its )?(stake|position|holdings)|sells? .* shares|buys? .* shares|institutional investor|institutional ownership|fund .* holding|asset manager .* stake|13f .* (stake|position|holding)).*"))return "INSTITUTIONAL_OWNERSHIP";
    if(low.matches(".*(backed|portfolio company|supplier to|customer of|partner of).*"))return "INDIRECT";
    if(hits.size()>1 || low.matches(".*(stocks? .* in focus|market today|wall st|s&p 500|nasdaq|dow .* (rise|fall|gain|loss)|sector .* (rise|fall|gain|loss)).*"))return "MARKET_CONTEXT";
    return "DIRECT";
  }
  static String newsMateriality(String low,String novelty,String evidence,String directness){
    // Market-flow observations are useful elsewhere, but are not corporate catalysts.
    if(low.matches(".*(options|contracts were traded|open interest|unusual options|call volume|put volume).*"))return "LOW";
    // Administrative settlement/claims updates are real news but usually low economic materiality for mega-cap issuers.
    if(low.matches(".*(claims now|submit claims|file a claim|claim your|payout for .* customers|eligible users|settlement claims).*"))return "LOW";
    if(low.contains("settlement")&&low.matches(".*(claim|eligible|payout|customers|owners|users).*"))return "LOW";
    if(Set.of("INDIRECT","INSTITUTIONAL_OWNERSHIP","FORMER_SPINOUT","MARKET_CONTEXT").contains(directness))return "LOW";
    if(evidence.equals("SPECULATIVE"))return "LOW";
    if(evidence.equals("ANALYST"))return "MEDIUM";
    if(novelty.equals("COMMENTARY")||novelty.equals("BACKGROUND"))return "LOW";
    if(low.matches(".*(raises guidance|cuts guidance|earnings|revenue|profit|eps|acquisition|merger|buyout|major contract|wins .* contract|fda approval|recall|ban|antitrust|export control).*"))return "HIGH";
    if(low.matches(".*(lawsuit|investigation|settlement|partnership|contract|order|customer|supplier|capacity|production).*"))return "MEDIUM";
    // A product launch is not automatically market-material. Require strategic/economic scope; ordinary ecosystem launches stay low.
    if(low.matches(".*(launch|unveil).*"))return low.matches(".*(data center|datacenter|ai infrastructure|platform|new chip|gpu|cpu|major|flagship).*" )?"MEDIUM":"LOW";
    return "MEDIUM";
  }
  // V5M.8.2.1 Catalyst Freshness Guard. Publication/source authority cannot make stale news current.
  // 0-24h: realtime candidate; 24-72h: recent/follow-through only; 3-7d: background only; >7d: excluded.
  static long newsAgeMinutes(String published){
    try{
      long age=Duration.between(ZonedDateTime.parse(published,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),Instant.now()).toMinutes();
      // Small clock skew is harmless; materially future-dated items are invalid for realtime evidence.
      if(age < -30)return Long.MAX_VALUE;
      return Math.max(0,age);
    }catch(Exception e){return Long.MAX_VALUE;}
  }
  static String applyNewsFreshness(String novelty,String published){
    long age=newsAgeMinutes(published);
    if(age==Long.MAX_VALUE||age>10080)return "REJECTED";       // >7 days: historical, never realtime evidence
    if(age>4320)return "BACKGROUND";                           // 3-7 days: background only
    if(age>1440&&novelty.equals("NEW_CATALYST"))return "FOLLOW_THROUGH"; // 24-72h cannot be a new catalyst
    return novelty;
  }
  static int catalystScore(String low,int source,int relevance,String published,String novelty,String evidence,String materiality,String directness){
    int x=(source>=95?18:source>=85?13:source>=75?9:4)+(relevance>=100?16:10);
    if(low.matches(".*(earnings|revenue|guidance|profit|eps|sales|raises guidance|cuts guidance|beats|misses).*"))x+=25;
    if(low.matches(".*(acquisition|merger|acquire|buyout|partnership|contract|investment|stake|funding|order|customer|supplier).*"))x+=20;
    if(low.matches(".*(sec |investigation|lawsuit|settlement|recall|ban|approval|fda|antitrust|tariff|export control).*"))x+=18;
    if(low.matches(".*(launch|unveil|new chip|gpu|cpu|data center|datacenter|ai model|semiconductor).*"))x+=13;
    if(low.matches(".*(analyst|price target|upgrade|downgrade|rating).*"))x+=8;
    x+=switch(novelty){case "NEW_CATALYST"->18;case "FOLLOW_THROUGH"->5;case "COMMENTARY"->-24;case "BACKGROUND"->-10;default->-60;};
    x+=switch(materiality){case "HIGH"->14;case "MEDIUM"->2;default->-22;};
    x+=switch(evidence){case "CONFIRMED"->8;case "ANALYST"->0;case "SPECULATIVE"->-20;default->2;};
    if(Set.of("INDIRECT","INSTITUTIONAL_OWNERSHIP","FORMER_SPINOUT","MARKET_CONTEXT").contains(directness))x-=24;
    else if(directness.equals("ANALYST"))x-=8;
    if(low.matches(".*(options|open interest|contracts were traded|unusual options).*"))x-=28;
    try{long age=newsAgeMinutes(published);if(age>=0&&age<=120)x+=12;else if(age<=360)x+=7;else if(age>1440)x-=12;if(age>4320)x-=18;if(age>10080)x-=35;}catch(Exception ignored){}
    return Math.max(0,Math.min(100,x));
  }
  static String catalystClass(int x,String novelty,String evidence,String materiality){
    if(novelty.equals("REJECTED"))return "IGNORE";
    if(materiality.equals("LOW")||evidence.equals("SPECULATIVE"))return x>=35?"BACKGROUND":"IGNORE";
    if(novelty.equals("COMMENTARY")||novelty.equals("BACKGROUND"))return x>=35?"BACKGROUND":"IGNORE";
    if(evidence.equals("ANALYST"))return x>=52?"SUPPORTING_CONTEXT":x>=35?"BACKGROUND":"IGNORE";
    if(materiality.equals("HIGH")&&novelty.equals("NEW_CATALYST")&&x>=72)return "ACTIONABLE_CATALYST";
    return x>=52?"SUPPORTING_CONTEXT":x>=35?"BACKGROUND":"IGNORE";
  }
  static int newsStateRank(String s){return switch(s){case "ACTIONABLE_CATALYST"->4;case "SUPPORTING_CONTEXT"->3;case "BACKGROUND"->2;default->1;};}
  static String catalystReason(String low,int x,String novelty,String evidence,String materiality,String directness){
    String prefix=switch(novelty){case "NEW_CATALYST"->"new event — ";case "FOLLOW_THROUGH"->"follow-through — ";case "COMMENTARY"->"commentary — ";default->"background — ";};
    String kind;
    if(low.matches(".*(analyst|price target|upgrade|downgrade|rating).*"))kind="analyst action";
    else if(low.matches(".*(options|open interest|contracts were traded|unusual options).*"))kind="market-flow observation, not a corporate catalyst";
    else if(low.matches(".*(earnings|revenue|guidance|profit|eps|sales|revenue goal|revenue target).*"))kind="earnings / guidance economics";
    else if(low.matches(".*(investigation|lawsuit|settlement|recall|ban|approval|antitrust|tariff|export control).*"))kind="regulatory / legal event";
    else if(low.matches(".*(acquisition|merger|partnership|contract|investment|stake|order|customer|supplier).*"))kind="business / capital event";
    else if(low.matches(".*(launch|unveil|new chip|gpu|cpu|data center|datacenter|ai model|semiconductor).*"))kind="product / technology event";
    else kind="company-specific context";
    return prefix+kind+" • "+evidence.toLowerCase(Locale.ROOT)+" evidence • "+materiality.toLowerCase(Locale.ROOT)+" materiality"+(!directness.equals("DIRECT")?" • "+directness.toLowerCase(Locale.ROOT).replace('_',' '):"");
  }

  static List<NewsItem> clusterNewsEvents(List<NewsItem> input){
    LinkedHashMap<String,List<NewsItem>> groups=new LinkedHashMap<>();
    for(NewsItem n:input)groups.computeIfAbsent(newsEventKey(n),k->new ArrayList<>()).add(n);
    List<NewsItem> out=new ArrayList<>();
    for(List<NewsItem> g:groups.values()){
      g.sort(Comparator.comparingInt((NewsItem n)->n.sourceScore()).reversed().thenComparingInt(NewsItem::catalystScore).reversed());
      NewsItem rep=g.get(0);LinkedHashSet<String> srcs=new LinkedHashSet<>();for(NewsItem n:g)srcs.add(n.source());
      int independent=srcs.size(), bestSource=g.stream().mapToInt(NewsItem::sourceScore).max().orElse(rep.sourceScore());
      int score=Math.min(100,rep.catalystScore()+(independent>=3?6:independent>=2?3:0));
      String cls=catalystClass(score,rep.novelty(),rep.evidence(),rep.materiality());
      String reason=rep.catalystReason()+(independent>1?" • "+independent+" independent reports":"");
      out.add(new NewsItem(rep.title(),rep.link(),rep.source(),rep.published(),rep.symbols(),rep.theme(),bestSource,rep.relevanceScore(),score,cls,reason,rep.novelty(),rep.provenance(),rep.evidence(),rep.materiality(),rep.directness(),independent,new ArrayList<>(srcs)));
    }
    return out;
  }
  static String newsEventKey(NewsItem n){
    String low=n.title().toLowerCase(Locale.ROOT), action="context";
    if(low.matches(".*(settlement|claims).*"))action="settlement";
    else if(low.matches(".*(downgrade|upgrade|price target|rating).*"))action="analyst";
    else if(low.matches(".*(launch|launches|launched|unveil|unveils|announces).*"))action="launch";
    else if(low.matches(".*(earnings|guidance|revenue|eps|profit).*"))action="earnings";
    else if(low.matches(".*(lawsuit|investigation|antitrust|recall|ban).*"))action="legal";
    else if(low.matches(".*(acquisition|merger|partnership|contract|order|investment|stake).*"))action="deal";
    String core=low.replaceAll("\\s+-\\s+[^-]{2,40}$","").replaceAll("[^a-z0-9 ]"," ")
      .replaceAll("\\b(the|a|an|and|or|to|of|for|on|in|with|after|says|report|reports|reported|announces|announced|launches|launched|new|now|stock|shares|corp|corporation|inc)\\b"," ").replaceAll("\\s+"," ").trim();
    String[] ws=core.split(" ");StringBuilder sig=new StringBuilder();int kept=0;for(String w:ws){if(w.length()<4)continue;if(kept++<5)sig.append(w).append('-');}
    List<String> sy=new ArrayList<>(n.symbols());Collections.sort(sy);
    // Event type + affected symbols is deliberate for highly distinctive events; signature separates simultaneous unrelated events.
    String distinctive=low.contains("photonlink")?"photonlink":low.contains("settlement")?"settlement":low.contains("rothschild redburn")?"rothschild-redburn":sig.toString();
    return String.join("+",sy)+"|"+action+"|"+distinctive;
  }

  static boolean newsJunk(String low){return low.matches(".*(\\$?1,?000 invested.*worth|could be worth by|should you buy.*stock|prediction for|where will .* stock be|millionaire-maker|top .* stocks to buy|best stocks to buy|best .* deals|deals you can shop|coupon|discount|if you invested|invested .* years ago|worth today|is now worth|since chatgpt launched|historical return|past .* years).*" );}
  static String newsDedupeKey(String title){return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]"," ").replaceAll("\\b(update|breaking|exclusive)\\b"," ").replaceAll("\\s+"," ").trim();}
  static String xmlTag(String s,String tag){var m=java.util.regex.Pattern.compile("(?is)<"+tag+"(?:\\s[^>]*)?>(.*?)</"+tag+">").matcher(s);return m.find()?m.group(1).trim():"";}
  static String xmlDecode(String s){return s.replace("<![CDATA[","").replace("]]>","").replace("&amp;","&").replace("&quot;","\\\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">");}
  static String newsAlias(String s){return switch(s){case "AMD"->"ADVANCED MICRO DEVICES";case "INTC"->"INTEL";case "NVDA"->"NVIDIA";case "META"->"META PLATFORMS";case "ARM"->"ARM HOLDINGS";case "MU"->"MICRON";case "AVGO"->"BROADCOM";case "TSM"->"TAIWAN SEMICONDUCTOR";case "COHR"->"COHERENT";case "MSTR"->"MICROSTRATEGY";case "AAPL"->"APPLE";case "WDC"->"WESTERN DIGITAL";case "STX"->"SEAGATE";case "COIN"->"COINBASE";case "VRT"->"VERTIV";case "VST"->"VISTRA";case "CEG"->"CONSTELLATION ENERGY";case "PWR"->"QUANTA SERVICES";case "BE"->"BLOOM ENERGY";case "ILMN"->"ILLUMINA";case "ALAB"->"ASTERA LABS";case "CRDO"->"CREDO TECHNOLOGY";case "CRWV"->"COREWEAVE";case "NBIS"->"NEBIUS";case "TEM"->"TEMPUS AI";case "CLS"->"CELESTICA";case "ETN"->"EATON";case "GEV"->"GE VERNOVA";case "LEU"->"CENTRUS ENERGY";case "PSTG"->"PURE STORAGE";case "CRCL"->"CIRCLE INTERNET";case "NET"->"CLOUDFLARE";case "NOW"->"SERVICENOW";case "ON"->"ONSEMI";case "IT"->"GARTNER";case "ALL"->"ALLSTATE";case "AI"->"C3.AI";default->"";};}
  static int sourceScore(String source){String s=source.toLowerCase(Locale.ROOT);if(s.contains("reuters"))return 100;if(s.contains("schwab"))return 95;if(s.contains("sec")||s.contains("business wire")||s.contains("globe newswire"))return 90;if(s.contains("cnbc")||s.contains("bloomberg")||s.contains("associated press"))return 85;if(s.contains("barron"))return 82;if(s.contains("yahoo finance")||s.contains("marketwatch"))return 78;if(s.contains("thestreet")||s.contains("the street")||s.contains("benzinga"))return 74;return 60;}
  static String newsTheme(String s){if(s.matches(".*(investigation|lawsuit|settlement|recall|ban|approval|antitrust|export control).*"))return "Legal / Regulatory";if(s.matches(".*(earnings|revenue|guidance|profit|eps|sales).*"))return "Earnings / Guidance";if(s.matches(".*(acquisition|merger|partnership|contract|buyout|stake|investment).*"))return "Deals / Partnerships";if(s.matches(".*(ai|artificial intelligence|data center|datacenter|gpu|cpu|semiconductor|chip).*"))return "AI / Compute / Semiconductors";if(s.matches(".*(fed|rate|yield|inflation|cpi|jobs|payroll|treasury).*"))return "Rates / Macro";if(s.matches(".*(bitcoin|crypto|ethereum).*"))return "Crypto";return "Company / Market News";}

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
    HttpResponse<String> qr,br;
    try{
      qr=alpacaGet("https://data.alpaca.markets/v2/stocks/"+enc+"/quotes/latest",feed);
      br=alpacaGet("https://data.alpaca.markets/v2/stocks/"+enc+"/bars/latest",feed);
    }catch(Exception e){
      System.out.println("Market data diag: "+sym+" phase="+phase+" feed="+feed+" request=EXCEPTION type="+e.getClass().getSimpleName()+" message="+String.valueOf(e.getMessage()));
      throw e;
    }
    if(qr.statusCode()!=200&&br.statusCode()!=200){
      System.out.println("Market data diag: "+sym+" phase="+phase+" feed="+feed+" quoteHTTP="+qr.statusCode()+" barHTTP="+br.statusCode()+" result=NO_DATA");
      json(x,200,"{\"provider\":\"ALPACA\",\"available\":false,\"feed\":"+q(feed)+",\"http\":"+Math.max(qr.statusCode(),br.statusCode())+",\"reason\":\"No latest quote/bar from selected feed\"}");return;
    }
    String qb=qr.statusCode()==200?qr.body():"",bb=br.statusCode()==200?br.body():"";
    String bid=jsonNum(qb,"bp"),ask=jsonNum(qb,"ap"),bs=jsonNum(qb,"bs"),as=jsonNum(qb,"as"),last=jsonNum(bb,"c"),barVol=jsonNum(bb,"v");
    String quoteTs=jsonStr(qb,"t"),barTs=jsonStr(bb,"t"); double bd=dnum(bid),ad=dnum(ask),ld=dnum(last); if(Double.isNaN(ld)&&!Double.isNaN(bd)&&!Double.isNaN(ad))last=String.format(Locale.US,"%.6f",(bd+ad)/2.0);
    boolean available=!Double.isNaN(dnum(last))||(!Double.isNaN(bd)&&!Double.isNaN(ad));
    long nowMs=System.currentTimeMillis(),quoteAgeMin=-1,barAgeMin=-1;
    try{if(!quoteTs.isBlank())quoteAgeMin=Math.max(0,(nowMs-Instant.parse(quoteTs).toEpochMilli())/60000);}catch(Exception ignored){}
    try{if(!barTs.isBlank())barAgeMin=Math.max(0,(nowMs-Instant.parse(barTs).toEpochMilli())/60000);}catch(Exception ignored){}
    long freshLimit=phase.equals("REGULAR")?12:20;
    boolean quoteFresh=available&&quoteAgeMin>=0&&quoteAgeMin<=freshLimit;
    boolean barFresh=available&&barAgeMin>=0&&barAgeMin<=freshLimit;
    boolean fresh=quoteFresh||barFresh;
    String routedProvider="ALPACA",routedFeed=feed;
    // V5H.3: IEX latest can be Friday-stale during Monday PRE/extended hours.
    // If so, route the price-only live layer to Yahoo includePrePost candles.
    // Bid/ask liquidity remains unavailable unless Alpaca itself is fresh.
    if(!fresh&&!phase.equals("REGULAR")){
      try{
        List<PricePoint> yp=historicalPoints(sym);
        if(!yp.isEmpty()){
          PricePoint y=yp.get(yp.size()-1);
          long yAge=Math.max(0,(nowMs-y.ts())/60000);
          System.out.println("Market data route: "+sym+" phase="+phase+" alpacaFeed="+feed+" yahooAgeMin="+yAge+" action="+(yAge<=20?"YAHOO_EXTENDED":"REJECT_STALE_YAHOO"));
          if(yAge<=20){
            ld=y.close(); last=Double.toString(ld); barTs=Instant.ofEpochMilli(y.ts()).toString();
            barAgeMin=yAge; barFresh=true; fresh=true; available=true;
            routedProvider="YAHOO_EXTENDED"; routedFeed="yahoo-includePrePost";
            // Do not reuse stale Alpaca spread/size as current liquidity.
            bid="null";ask="null";bs="null";as="null";bd=Double.NaN;ad=Double.NaN;
          }
        }else System.out.println("Market data route: "+sym+" phase="+phase+" action=YAHOO_EMPTY");
      }catch(Exception ye){
        System.out.println("Market data route: "+sym+" phase="+phase+" action=YAHOO_ERROR type="+ye.getClass().getSimpleName()+" message="+String.valueOf(ye.getMessage()));
      }
    }
    String diagResult=fresh?"FRESH":available?"STALE":"EMPTY";
    System.out.println("Market data diag: "+sym+" phase="+phase+" feed="+feed+" quoteHTTP="+qr.statusCode()+" barHTTP="+br.statusCode()+" quoteTs="+(quoteTs.isBlank()?"NONE":quoteTs)+" quoteAgeMin="+quoteAgeMin+" barTs="+(barTs.isBlank()?"NONE":barTs)+" barAgeMin="+barAgeMin+" available="+available+" result="+diagResult);
    json(x,200,"{\"provider\":"+q(routedProvider)+",\"available\":"+available+",\"fresh\":"+fresh+",\"quoteFresh\":"+quoteFresh+",\"barFresh\":"+barFresh+",\"freshLimitMin\":"+freshLimit+",\"quoteAgeMin\":"+quoteAgeMin+",\"barAgeMin\":"+barAgeMin+",\"feed\":"+q(routedFeed)+",\"phase\":"+q(phase)+",\"bid\":"+bid+",\"ask\":"+ask+",\"bidSize\":"+bs+",\"askSize\":"+as+",\"last\":"+last+",\"minuteVolume\":"+barVol+",\"quoteTs\":"+q(quoteTs)+",\"barTs\":"+q(barTs)+"}");
  }
  static String alpacaBarsAsYahoo(String sym,String body,String provider,String quality)throws Exception{
    // Parse each flat Alpaca bar object by key instead of depending on JSON field order.
    // This keeps V5P.2 tolerant of provider-side serialization order changes.
    var objPat=java.util.regex.Pattern.compile("\\{[^{}]{20,900}\\}");
    var om=objPat.matcher(body);StringBuilder ts=new StringBuilder(),op=new StringBuilder(),hi=new StringBuilder(),lo=new StringBuilder(),cl=new StringBuilder(),vo=new StringBuilder();int n=0;
    while(om.find()){
      String o=om.group();String c=jsonNum(o,"c"),h=jsonNum(o,"h"),l=jsonNum(o,"l"),open=jsonNum(o,"o"),v=jsonNum(o,"v"),t=jsonStr(o,"t");
      if(c.equals("null")||h.equals("null")||l.equals("null")||open.equals("null")||t.isBlank())continue;
      try{long epoch=Instant.parse(t).getEpochSecond();if(n++>0){ts.append(',');op.append(',');hi.append(',');lo.append(',');cl.append(',');vo.append(',');}ts.append(epoch);cl.append(c);hi.append(h);lo.append(l);op.append(open);vo.append(v.equals("null")?"0":v);}catch(Exception ignored){}
    }
    if(n==0)throw new Exception("No usable Alpaca bars for "+sym);
    return "{\"chart\":{\"result\":[{\"meta\":{\"symbol\":"+q(sym)+",\"provider\":"+q(provider)+",\"quality\":"+q(quality)+",\"barCount\":"+n+"},\"timestamp\":["+ts+"],\"indicators\":{\"quote\":[{\"open\":["+op+"],\"high\":["+hi+"],\"low\":["+lo+"],\"close\":["+cl+"],\"volume\":["+vo+"]}]}}],\"error\":null}}";
  }

  static void overnightBars(HttpExchange x,String raw)throws Exception{
    String sym=raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.-]","");if(sym.isBlank())throw new Exception("Invalid ticker");
    Properties p=marketSettings();if(!p.getProperty("provider","YAHOO").equals("ALPACA")){json(x,200,"{\"available\":false,\"reason\":\"Alpaca not configured\"}");return;}
    String enc=URLEncoder.encode(sym,StandardCharsets.UTF_8);ZonedDateTime now=ZonedDateTime.now(ZoneOffset.UTC),start=now.minusHours(12),delayedEnd=now.minusMinutes(16);
    String u="https://data.alpaca.markets/v2/stocks/"+enc+"/bars?timeframe=5Min&start="+URLEncoder.encode(start.toInstant().toString(),StandardCharsets.UTF_8)+"&end="+URLEncoder.encode(delayedEnd.toInstant().toString(),StandardCharsets.UTF_8)+"&limit=500&adjustment=raw";
    HttpResponse<String> hist=alpacaGet(u,"boats");String hb=hist.statusCode()==200?hist.body():"";
    HttpResponse<String> latest=alpacaGet("https://data.alpaca.markets/v2/stocks/"+enc+"/bars/latest","overnight");String lb=latest.statusCode()==200?latest.body():"";
    String combined=hb;
    // Free-plan BOATS history is delayed, while feed=overnight latest bar is current. Append the latest real bar; never synthesize missing bars.
    if(!lb.isBlank()&&lb.contains("\"bar\"")){var bm=java.util.regex.Pattern.compile("\\\"bar\\\"\\s*:\\s*(\\{[^}]+\\})").matcher(lb);if(bm.find())combined=hb+bm.group(1);}
    try{String out=alpacaBarsAsYahoo(sym,combined,"ALPACA_OVERNIGHT","REALTIME_LATEST_PLUS_DELAYED_BOATS_HISTORY");bytes(x,200,"application/json; charset=utf-8",out.getBytes(StandardCharsets.UTF_8));}
    catch(Exception e){json(x,200,"{\"available\":false,\"provider\":\"ALPACA_OVERNIGHT\",\"historicalHttp\":"+hist.statusCode()+",\"latestHttp\":"+latest.statusCode()+",\"reason\":"+q(e.getMessage())+"}");}
  }



  static String feedBarDiag(String feed,HttpResponse<String> r){
    String b=r.body()==null?"":r.body();int count=0;String latest="";long age=-1;
    var m=java.util.regex.Pattern.compile("\\{[^{}]{20,900}\\}").matcher(b);
    while(m.find()){String t=jsonStr(m.group(),"t");if(!t.isBlank()){count++;latest=t;}}
    try{if(!latest.isBlank())age=Math.max(0,(System.currentTimeMillis()-Instant.parse(latest).toEpochMilli())/60000);}catch(Exception ignored){}
    String msg=jsonStr(b,"message");
    return "{\"feed\":"+q(feed)+",\"http\":"+r.statusCode()+",\"barCount\":"+count+",\"latestTs\":"+q(latest)+",\"latestAgeMin\":"+age+",\"message\":"+q(msg)+"}";
  }
  static void feedDiagnostics(HttpExchange x,String raw)throws Exception{
    String sym=raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.-]","");if(sym.isBlank())throw new Exception("Invalid ticker");
    Properties p=marketSettings();if(!p.getProperty("provider","YAHOO").equals("ALPACA")){json(x,200,"{\"available\":false,\"reason\":\"Alpaca not configured\"}");return;}
    String enc=URLEncoder.encode(sym,StandardCharsets.UTF_8);ZonedDateTime now=ZonedDateTime.now(ZoneOffset.UTC),start=now.minusHours(18);
    String u="https://data.alpaca.markets/v2/stocks/"+enc+"/bars?timeframe=5Min&start="+URLEncoder.encode(start.toInstant().toString(),StandardCharsets.UTF_8)+"&end="+URLEncoder.encode(now.toInstant().toString(),StandardCharsets.UTF_8)+"&limit=1000&adjustment=raw";
    // V5P.2.7 diagnostics mirror the feeds MarketLedger can actually use on this account.
    // Do not probe SIP (known entitlement 403) or invalid delayed_sip.
    StringBuilder fs=new StringBuilder();
    HttpResponse<String> iex=alpacaGet(u,"iex");fs.append(feedBarDiag("iex",iex));
    HttpResponse<String> boats=alpacaGet(u,"boats");fs.append(',').append(feedBarDiag("boats",boats));
    HttpResponse<String> overnightLatest=alpacaGet("https://data.alpaca.markets/v2/stocks/"+enc+"/bars/latest","overnight");
    fs.append(',').append(feedBarDiag("overnight_latest",overnightLatest));
    long yahooAge=-1;String yahooTs="";int yahooCount=0;String yahooErr="";
    try{List<PricePoint> yp=historicalPoints(sym);yahooCount=yp.size();if(!yp.isEmpty()){PricePoint y=yp.get(yp.size()-1);yahooAge=Math.max(0,(System.currentTimeMillis()-y.ts())/60000);yahooTs=Instant.ofEpochMilli(y.ts()).toString();}}catch(Exception e){yahooErr=String.valueOf(e.getMessage());}
    json(x,200,"{\"symbol\":"+q(sym)+",\"phase\":"+q(marketPhaseServer())+",\"serverTime\":"+q(Instant.now().toString())+",\"alpaca\":["+fs+"],\"yahoo\":{\"barCount\":"+yahooCount+",\"latestTs\":"+q(yahooTs)+",\"latestAgeMin\":"+yahooAge+",\"error\":"+q(yahooErr)+"}}");
  }

  static void sessionBars(HttpExchange x,String raw)throws Exception{
    String sym=raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.-]","");if(sym.isBlank())throw new Exception("Invalid ticker");
    Properties p=marketSettings();if(!p.getProperty("provider","YAHOO").equals("ALPACA")){json(x,200,"{\"available\":false,\"reason\":\"Alpaca not configured\"}");return;}
    String phase=marketPhaseServer();if(!phase.equals("PRE")&&!phase.equals("REGULAR")&&!phase.equals("POST")){json(x,200,"{\"available\":false,\"phase\":"+q(phase)+",\"reason\":\"Session bars are used for PRE/REGULAR/POST\"}");return;}
    String enc=URLEncoder.encode(sym,StandardCharsets.UTF_8);ZonedDateTime now=ZonedDateTime.now(ZoneOffset.UTC),start=now.minusHours(18);
    String u="https://data.alpaca.markets/v2/stocks/"+enc+"/bars?timeframe=5Min&start="+URLEncoder.encode(start.toInstant().toString(),StandardCharsets.UTF_8)+"&end="+URLEncoder.encode(now.toInstant().toString(),StandardCharsets.UTF_8)+"&limit=1000&adjustment=raw";
    // V5P.2.7: this deployment's Alpaca entitlement does not include recent SIP bars.
    // Use the supported IEX feed directly instead of generating a recurring SIP 403 on every symbol.
    HttpResponse<String> hist=alpacaGet(u,"iex");
    HttpResponse<String> latest=alpacaGet("https://data.alpaca.markets/v2/stocks/"+enc+"/bars/latest","iex");
    String selectedFeed="iex";
    String hb=hist.statusCode()==200?hist.body():"",lb=latest.statusCode()==200?latest.body():"";
    String combined=hb;if(!lb.isBlank()&&lb.contains("\"bar\"")){var bm=java.util.regex.Pattern.compile("\"bar\"\\s*:\\s*(\\{[^}]+\\})").matcher(lb);if(bm.find())combined=hb+bm.group(1);}
    try{String out=alpacaBarsAsYahoo(sym,combined,"ALPACA_SESSION_"+selectedFeed.toUpperCase(Locale.ROOT),"CURRENT_SESSION_"+selectedFeed.toUpperCase(Locale.ROOT)+"_HISTORY_PLUS_LATEST");bytes(x,200,"application/json; charset=utf-8",out.getBytes(StandardCharsets.UTF_8));}
    catch(Exception e){json(x,200,"{\"available\":false,\"provider\":"+q("ALPACA_SESSION_"+selectedFeed.toUpperCase(Locale.ROOT))+",\"feed\":"+q(selectedFeed)+",\"phase\":"+q(phase)+",\"historicalHttp\":"+hist.statusCode()+",\"latestHttp\":"+latest.statusCode()+",\"reason\":"+q(e.getMessage())+"}");}
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
    try{HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).build();HttpResponse<String> r=client.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(10)).header("User-Agent","Mozilla/5.0 MarketLedger/2.5").header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());String body=r.body();if(r.statusCode()==200&&body!=null&&!body.contains("\"result\":null")&&body.contains("\"timestamp\"")){bytes(x,200,"application/json; charset=utf-8",body.getBytes(StandardCharsets.UTF_8));return;}}catch(Exception ignored){}
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
    Map<String,String> f=form(x); String sym=req(f,"symbol").toUpperCase(Locale.ROOT); long ts=Long.parseLong(req(f,"candleTs")); double price=num(req(f,"price")); Signal current=null;
    synchronized(LOCK){var a=readSignals(); boolean changed=false;
      for(int i=0;i<a.size();i++){var z=a.get(i); if(!z.symbol.equalsIgnoreCase(sym))continue; long age=ts-z.candleTs; double r15=z.r15,r30=z.r30,r60=z.r60;
        if(age>=15*60_000L && Double.isNaN(r15)){r15=(price/z.price-1)*100;changed=true;} if(age>=30*60_000L && Double.isNaN(r30)){r30=(price/z.price-1)*100;changed=true;} if(age>=60*60_000L && Double.isNaN(r60)){r60=(price/z.price-1)*100;changed=true;}
        if(r15!=z.r15||r30!=z.r30||r60!=z.r60)a.set(i,new Signal(z.id,z.symbol,z.candleTs,z.captured,z.price,z.signal,z.score,z.phase,z.rsi,z.vwap,z.trend,z.volRatio,z.atrPct,z.spreadPct,z.marketText,z.eventText,r15,r30,r60));
      }
      for(var z:a)if(z.symbol.equalsIgnoreCase(sym)&&z.candleTs==ts){current=z;break;}
      if(current==null){long id=a.stream().mapToLong(Signal::id).max().orElse(0)+1;current=new Signal(id,sym,ts,now(),price,req(f,"signal"),(int)num(f.get("score")),f.getOrDefault("phase",""),num0(f.get("rsi")),num0(f.get("vwap")),num0(f.get("trend")),num0(f.get("volRatio")),num0(f.get("atrPct")),num0(f.get("spreadPct")),f.getOrDefault("marketText",""),f.getOrDefault("eventText",""),Double.NaN,Double.NaN,Double.NaN);a.add(0,current);changed=true;}
      if(changed)writeSignals(a);
    }
    if(current!=null)upsertSignalNative(current); ok(x);
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
  static void writeSignals(List<Signal>a)throws IOException{var l=new ArrayList<String>();for(var z:a)l.add(z.id+"\t"+en(z.symbol)+"\t"+z.candleTs+"\t"+en(z.captured)+"\t"+z.price+"\t"+en(z.signal)+"\t"+z.score+"\t"+en(z.phase)+"\t"+z.rsi+"\t"+z.vwap+"\t"+z.trend+"\t"+z.volRatio+"\t"+z.atrPct+"\t"+z.spreadPct+"\t"+en(z.marketText)+"\t"+en(z.eventText)+"\t"+(Double.isNaN(z.r15)?"":z.r15)+"\t"+(Double.isNaN(z.r30)?"":z.r30)+"\t"+(Double.isNaN(z.r60)?"":z.r60));atomicSignals(SIGNALS,l);}
  // Signal capture is high-frequency. Do not rebuild every native table for every ticker.
  // The TSV/blob remains the compatibility backup; recordSignal() upserts only the current
  // observation into the native analytics tables and the outcome worker fills outcomes.
  static void atomicSignals(Path p,List<String>l)throws IOException{Path t=p.resolveSibling(p.getFileName()+".tmp");Files.write(t,l,StandardCharsets.UTF_8);try{Files.move(t,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){Files.move(t,p,StandardCopyOption.REPLACE_EXISTING);}persistFile(p);}
  static void upsertSignalNative(Signal z)throws IOException{if(!DATABASE_READY)return;try(Connection c=db();PreparedStatement so=c.prepareStatement("INSERT INTO signal_observations(id,symbol,candle_ts,captured_at,price,final_status,technical_score,session,rsi14,vwap,momentum_5m,relative_volume,atr_pct,spread_pct,market_context,event_context,captured_at_ts) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz) ON CONFLICT(id) DO UPDATE SET symbol=EXCLUDED.symbol,candle_ts=EXCLUDED.candle_ts,captured_at=EXCLUDED.captured_at,price=EXCLUDED.price,final_status=EXCLUDED.final_status,technical_score=EXCLUDED.technical_score,session=EXCLUDED.session,rsi14=EXCLUDED.rsi14,vwap=EXCLUDED.vwap,momentum_5m=EXCLUDED.momentum_5m,relative_volume=EXCLUDED.relative_volume,atr_pct=EXCLUDED.atr_pct,spread_pct=EXCLUDED.spread_pct,market_context=EXCLUDED.market_context,event_context=EXCLUDED.event_context,captured_at_ts=EXCLUDED.captured_at_ts");PreparedStatement out=c.prepareStatement("INSERT INTO signal_outcomes(signal_id,return_15m,return_30m,return_60m) VALUES(?,?,?,?) ON CONFLICT(signal_id) DO NOTHING")){so.setLong(1,z.id);so.setString(2,z.symbol);so.setLong(3,z.candleTs);so.setString(4,z.captured);so.setDouble(5,z.price);so.setString(6,z.signal);so.setInt(7,z.score);so.setString(8,z.phase);so.setDouble(9,z.rsi);so.setDouble(10,z.vwap);so.setDouble(11,z.trend);so.setDouble(12,z.volRatio);so.setDouble(13,z.atrPct);so.setDouble(14,z.spreadPct);so.setString(15,z.marketText);so.setString(16,z.eventText);so.setString(17,z.captured==null||z.captured.isBlank()?null:z.captured+"Z");so.executeUpdate();out.setLong(1,z.id);if(Double.isNaN(z.r15))out.setNull(2,Types.DOUBLE);else out.setDouble(2,z.r15);if(Double.isNaN(z.r30))out.setNull(3,Types.DOUBLE);else out.setDouble(3,z.r30);if(Double.isNaN(z.r60))out.setNull(4,Types.DOUBLE);else out.setDouble(4,z.r60);out.executeUpdate();}catch(Exception e){throw new IOException("Signal PostgreSQL upsert failed: "+e.getMessage(),e);}}

  static void exportCsv(HttpExchange x)throws IOException{StringBuilder b=new StringBuilder("id,symbol,direction,baseline,threshold_pct,due,status,resolved_price,move_pct,note,created,resolved_at\n");for(var c:readCalls())b.append(c.id).append(',').append(csv(c.symbol)).append(',').append(c.direction).append(',').append(c.baseline).append(',').append(c.threshold).append(',').append(c.due).append(',').append(c.status).append(',').append(c.resolved).append(',').append(c.move).append(',').append(csv(c.note)).append(',').append(csv(c.created)).append(',').append(csv(c.resolvedAt)).append('\n');byte[] z=b.toString().getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/csv; charset=utf-8");x.getResponseHeaders().set("Content-Disposition","attachment; filename=market-ledger.csv");x.sendResponseHeaders(200,z.length);x.getResponseBody().write(z);x.close();}

  static void seed()throws IOException{ synchronized(LOCK){if(!Files.exists(STOCKS)){writeStocks(new ArrayList<>(List.of(new Stock("NVDA","NVIDIA",0,0,""),new Stock("MU","Micron Technology",0,0,""),new Stock("COIN","Coinbase",0,0,""),new Stock("WDC","Western Digital",0,0,""),new Stock("STX","Seagate Technology",0,0,""),new Stock("BE","Bloom Energy",0,0,""),new Stock("ILMN","Illumina",0,0,""))));} if(!Files.exists(CALLS))writeCalls(new ArrayList<>()); if(!Files.exists(SIGNALS))writeSignals(new ArrayList<>()); if(!Files.exists(EVENTS))writeEvents(new ArrayList<>(List.of(new Event(1,"2026-09-21 09:30","REBALANCE","HIGH","BE","S&P 500 addition effective at Monday open",now()),new Event(2,"2026-09-21 09:30","REBALANCE","HIGH","ILMN","S&P 500 addition effective at Monday open",now()),new Event(3,"2026-09-23 09:45","ECONOMIC","HIGH","ALL","S&P Global PMI Index",now()),new Event(4,"2026-09-24 08:30","ECONOMIC","HIGH","ALL","Initial Jobless Claims",now()),new Event(5,"2026-09-25 08:30","ECONOMIC","HIGH","ALL","Durable Goods Orders",now())))); if(!Files.exists(NOTES))writeNotes(new ArrayList<>(List.of(new Note(1,"Options expiration / rebalance","Track the event, then record what actually happened. Treat directional claims as hypotheses, not guarantees.","EVENT",now()),new Note(2,"Sectors discussed","Memory / semiconductors, crypto-linked equities, storage, and index additions were recurring themes in the source conversation.","CONTEXT",now()))));}}
  static List<Stock> readStocks()throws IOException{var bySymbol=new LinkedHashMap<String,Stock>();if(!Files.exists(STOCKS))return new ArrayList<>();for(String l:Files.readAllLines(STOCKS)){if(l.isBlank())continue;String[]p=l.split("\\t",-1);if(p.length<5)continue;var z=new Stock(un(p[0]),un(p[1]),d(p[2]),d(p[3]),un(p[4]));String key=z.symbol==null?"":z.symbol.trim().toUpperCase(Locale.ROOT);if(!key.isBlank())bySymbol.put(key,z);}return new ArrayList<>(bySymbol.values());}
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
