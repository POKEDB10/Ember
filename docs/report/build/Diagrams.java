import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Draws the report's UML figures as PNG (2x scale) with Java 2D. Run: java Diagrams.java outDir */
public class Diagrams {
    static final double S = 2.0;
    static final Font NAME = new Font("Arial", Font.BOLD, 13), BODY = new Font("Arial", Font.PLAIN, 11),
            ITAL = new Font("Arial", Font.ITALIC, 11), LABEL = new Font("Arial", Font.PLAIN, 10);
    static final Color INK = new Color(0x222222), FILL = new Color(0xFFF7EC), IFACE = new Color(0xEEF3FF),
            EXC = new Color(0xFFEDED), EDGE = new Color(0x444444), NOTE = new Color(0xFFFBD6);

    record Box(String id, double x, double y, double w, double h, String stereo, String name,
            List<String> attrs, List<String> ops, Color fill) {
        double cx() { return x + w / 2; }
        double cy() { return y + h / 2; }
    }

    static Map<String, Box> boxes = new LinkedHashMap<>();
    static Graphics2D g;

    static BufferedImage canvas(int w, int h) {
        BufferedImage img = new BufferedImage((int) (w * S), (int) (h * S), BufferedImage.TYPE_INT_RGB);
        g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(S, S);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        boxes.clear();
        return img;
    }

    static Box cls(String id, double x, double y, double w, String stereo, String name, String attrs, String ops, Color fill) {
        List<String> a = attrs.isEmpty() ? List.of() : List.of(attrs.split("\\|"));
        List<String> o = ops.isEmpty() ? List.of() : List.of(ops.split("\\|"));
        double h = 22 + (stereo.isEmpty() ? 0 : 13) + 8 + a.size() * 14 + 8 + o.size() * 14 + 4;
        Box b = new Box(id, x, y, w, h, stereo, name, a, o, fill);
        boxes.put(id, b);
        g.setColor(fill);
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Rectangle2D.Double(x, y, w, h));
        double ty = y + 16;
        if (!stereo.isEmpty()) {
            center("«" + stereo + "»", ITAL, x + w / 2, ty);
            ty += 13;
        }
        center(name, stereo.equals("interface") ? new Font("Arial", Font.BOLD | Font.ITALIC, 13) : NAME, x + w / 2, ty);
        double line = ty + 8;
        g.draw(new Line2D.Double(x, line, x + w, line));
        g.setFont(BODY);
        double yy = line + 13;
        for (String s : a) { g.drawString(s, (float) (x + 6), (float) yy); yy += 14; }
        line = yy - 6 + 4;
        g.draw(new Line2D.Double(x, line, x + w, line));
        yy = line + 13;
        for (String s : o) { g.drawString(s, (float) (x + 6), (float) yy); yy += 14; }
        return b;
    }

    static void center(String text, Font f, double cx, double y) {
        g.setFont(f);
        FontMetrics m = g.getFontMetrics();
        g.drawString(text, (float) (cx - m.stringWidth(text) / 2.0), (float) y);
    }

    /** Point where the line from the box centre towards (tx,ty) leaves the box. */
    static Point2D exit(Box b, double tx, double ty) {
        double dx = tx - b.cx(), dy = ty - b.cy();
        if (dx == 0 && dy == 0) return new Point2D.Double(b.cx(), b.cy());
        double sx = dx == 0 ? Double.MAX_VALUE : (b.w() / 2) / Math.abs(dx);
        double sy = dy == 0 ? Double.MAX_VALUE : (b.h() / 2) / Math.abs(dy);
        double s = Math.min(sx, sy);
        return new Point2D.Double(b.cx() + dx * s, b.cy() + dy * s);
    }

    enum Kind { INHERIT, REALIZE, ASSOC, COMPOSE, DEPEND, USE }

    static void edge(String from, String to, Kind k, String label) {
        Box a = boxes.get(from), b = boxes.get(to);
        Point2D p = exit(a, b.cx(), b.cy()), q = exit(b, a.cx(), a.cy());
        line(p, q, k, label);
    }

    /** Edge with explicit anchor points (for tidy vertical/horizontal routing). */
    static void edgeAt(double x1, double y1, double x2, double y2, Kind k, String label) {
        line(new Point2D.Double(x1, y1), new Point2D.Double(x2, y2), k, label);
    }

    static void line(Point2D p, Point2D q, Kind k, String label) {
        boolean dashed = k == Kind.REALIZE || k == Kind.DEPEND || k == Kind.USE;
        g.setColor(EDGE);
        g.setStroke(dashed ? new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {6, 4}, 0)
                : new BasicStroke(1.1f));
        double ang = Math.atan2(q.getY() - p.getY(), q.getX() - p.getX());
        double end = (k == Kind.INHERIT || k == Kind.REALIZE) ? 12 : 0;
        double start = k == Kind.COMPOSE ? 16 : 0;
        g.draw(new Line2D.Double(p.getX() + Math.cos(ang) * start, p.getY() + Math.sin(ang) * start,
                q.getX() - Math.cos(ang) * end, q.getY() - Math.sin(ang) * end));
        g.setStroke(new BasicStroke(1.1f));
        if (k == Kind.INHERIT || k == Kind.REALIZE) {
            Path2D t = arrowHead(q, ang, 12, 7);
            t.closePath();
            g.setColor(Color.WHITE);
            g.fill(t);
            g.setColor(EDGE);
            g.draw(t);
        } else if (k != Kind.USE) {
            g.draw(arrowHead(q, ang, 10, 5));
        }
        if (k == Kind.COMPOSE) {
            Path2D d = new Path2D.Double();
            double c = Math.cos(ang), s = Math.sin(ang);
            d.moveTo(p.getX(), p.getY());
            d.lineTo(p.getX() + c * 8 - s * 5, p.getY() + s * 8 + c * 5);
            d.lineTo(p.getX() + c * 16, p.getY() + s * 16);
            d.lineTo(p.getX() + c * 8 + s * 5, p.getY() + s * 8 - c * 5);
            d.closePath();
            g.fill(d);
        }
        if (label != null && !label.isEmpty()) {
            double mx = (p.getX() + q.getX()) / 2, my = (p.getY() + q.getY()) / 2;
            g.setFont(LABEL);
            FontMetrics m = g.getFontMetrics();
            int w = m.stringWidth(label);
            g.setColor(Color.WHITE);
            g.fill(new Rectangle2D.Double(mx - w / 2.0 - 2, my - 9, w + 4, 12));
            g.setColor(new Color(0x333366));
            g.drawString(label, (float) (mx - w / 2.0), (float) (my + 1));
        }
    }

    static Path2D arrowHead(Point2D q, double ang, double len, double half) {
        Path2D t = new Path2D.Double();
        double c = Math.cos(ang), s = Math.sin(ang);
        t.moveTo(q.getX() - c * len - s * half, q.getY() - s * len + c * half);
        t.lineTo(q.getX(), q.getY());
        t.lineTo(q.getX() - c * len + s * half, q.getY() - s * len - c * half);
        return t;
    }

    static void title(String text, int w) {
        // captions live in the report, so figures carry no title of their own
    }

    static void save(BufferedImage img, String out) throws Exception {
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("wrote " + out);
    }

    // ---------- figures ----------
    static void useCase(String dir) throws Exception {
        int W = 1120, H = 700;
        BufferedImage img = canvas(W, H);
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new RoundRectangle2D.Double(230, 40, 680, 640, 18, 18));
        center("Ember system", NAME, 570, 62);
        String[][] cases = {
            {"auth", "Register / log in on any device"}, {"msg", "Send and read messages (DM, group)"},
            {"react", "Reply, react, edit, pin, star"}, {"group", "Create groups and polls"},
            {"pulse", "Publish posts and stories"}, {"social", "Follow, approve requests, block, report"},
            {"status", "Set status (online / away / DND / invisible)"}, {"notify", "Get notifications, quick reply"},
            {"admin", "Review reports, unlock accounts"}};
        Map<String, double[]> pos = new HashMap<>();
        double y = 110;
        for (String[] c : cases) { pos.put(c[0], ellipse(430, y, 170, 24, c[1], FILL)); y += 68; }
        double[] store = ellipse(780, 110 + 68, 110, 26, "Keep data off-server", IFACE);
        double[] authz = ellipse(780, 110 + 68 * 3.5, 110, 26, "Authorize object access", IFACE);
        actor(95, 300, "Student");
        actor(95, 600, "Administrator");
        actor(1040, 160, "rootz.so");
        actor(1040, 110 + 68 * 7 - 20, "Push service");
        for (String c : List.of("auth", "msg", "react", "group", "pulse", "social", "status", "notify")) {
            double[] p = pos.get(c);
            assocLine(120, 300, p[0] - p[2], p[1]);
        }
        double[] admin = pos.get("admin"), notify = pos.get("notify"), msg = pos.get("msg");
        assocLine(120, 610, admin[0] - admin[2], admin[1]);
        edgeAt(95, 560, 95, 372, Kind.INHERIT, ""); // an administrator is also a student
        assocLine(notify[0] + notify[2], notify[1], 1015, notify[1]);
        assocLine(store[0] + store[2], store[1], 1015, store[1]);
        edgeAt(msg[0] + msg[2] - 8, msg[1] + 6, store[0] - store[2] + 4, store[1] - 4, Kind.DEPEND, "«include»");
        boolean first = true;
        for (String c : List.of("react", "group", "pulse", "social")) {
            double[] p = pos.get(c);
            edgeAt(p[0] + p[2] - 4, p[1], authz[0] - authz[2] + 2, authz[1], Kind.DEPEND, first ? "«include»" : "");
            first = false;
        }
        save(img, dir + "/fig1-use-case.png");
    }

    static double[] ellipse(double cx, double cy, double rw, double rh, String text, Color fill) {
        g.setColor(fill);
        g.fill(new Ellipse2D.Double(cx - rw, cy - rh, rw * 2, rh * 2));
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Ellipse2D.Double(cx - rw, cy - rh, rw * 2, rh * 2));
        center(text, BODY, cx, cy + 4);
        return new double[] {cx, cy, rw, rh};
    }

    static double p2(double[] p) { return p[2] - 20; }

    static void assocLine(double x1, double y1, double x2, double y2) {
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1.0f));
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    static void actor(double x, double y, String name) {
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(new Ellipse2D.Double(x - 9, y - 30, 18, 18));
        g.draw(new Line2D.Double(x, y - 12, x, y + 14));
        g.draw(new Line2D.Double(x - 16, y - 2, x + 16, y - 2));
        g.draw(new Line2D.Double(x, y + 14, x - 12, y + 32));
        g.draw(new Line2D.Double(x, y + 14, x + 12, y + 32));
        center(name, NAME, x, y + 50);
    }

    static void deployment(String dir) throws Exception {
        int W = 1150, H = 640;
        BufferedImage img = canvas(W, H);
        title("Figure 2 · Deployment: one server, every device, data off-server", W);
        node(40, 70, 300, 150, "Phone / laptop browser", "Website (HTML, CSS, JS)|Service worker: offline shell, push|IndexedDB chat cache (delta sync)|Installable as an app (PWA)");
        node(40, 300, 300, 120, "Swing desktop app", "ClientController + ServerLink|--url → WebSocket to the website|or direct TLS 1.3 socket (local)");
        node(430, 160, 330, 260, "Render free web service (Docker)", "EmberServer (web-only, --no-tls)|WebGateway: files, /media, /health, WSS|ConnectionHandler per device|WebPush (RFC 8291 + VAPID)|WalStorageEngine + local cache disk|BlobStoreReplica (batch every 2 s)|JRE 21, ~40 MB RAM idle");
        node(850, 70, 270, 120, "rootz.so  ·  folder \"ember\"", "ember/data: log + snapshots|ember/media: images|AES-256-GCM encrypted, GZIP");
        node(850, 260, 270, 100, "Browser push services", "FCM · Mozilla · WNS · APNs|wake the browser's service worker|when no Ember tab is open");
        node(850, 430, 270, 90, "UptimeRobot (free)", "GET /health every 5 minutes|keeps the free service awake");
        node(430, 470, 330, 110, "Cloudflare / Render edge", "HTTPS certificate for|ember.pokedb.site|X-Forwarded-For (--trust-proxy)");
        edgeAt(340, 145, 430, 230, Kind.ASSOC, "HTTPS + WSS (deflate)");
        edgeAt(340, 360, 430, 330, Kind.ASSOC, "WSS (--url)");
        edgeAt(760, 220, 850, 130, Kind.ASSOC, "encrypted batches");
        edgeAt(760, 300, 850, 310, Kind.ASSOC, "Web Push (encrypted)");
        edgeAt(850, 475, 760, 400, Kind.ASSOC, "/health");
        edgeAt(595, 470, 595, 420, Kind.USE, "");
        save(img, dir + "/fig2-deployment.png");
    }

    static void node(double x, double y, double w, double h, String name, String lines) {
        g.setColor(new Color(0xDDDDDD));
        Path2D side = new Path2D.Double();
        side.moveTo(x, y); side.lineTo(x + 10, y - 10); side.lineTo(x + w + 10, y - 10); side.lineTo(x + w + 10, y + h - 10);
        side.lineTo(x + w, y + h); side.lineTo(x + w, y); side.closePath();
        g.fill(side);
        g.setColor(INK);
        g.draw(side);
        g.setColor(FILL);
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new Rectangle2D.Double(x, y, w, h));
        center(name, NAME, x + w / 2, y + 20);
        g.setFont(BODY);
        double yy = y + 40;
        for (String s : lines.split("\\|")) { g.drawString("• " + s, (float) (x + 10), (float) yy); yy += 15; }
    }

    static void serverClasses(String dir) throws Exception {
        int W = 1180, H = 900;
        BufferedImage img = canvas(W, H);
        title("Figure 3 · Server and storage classes", W);
        cls("server", 40, 50, 300, "", "EmberServer", "- online: Map<String, Set<ConnectionHandler>>|- statuses: Map<String, String>|- webPush: WebPush", "+ start() / startHttp(port, bind)|- sendToUser(user, frame, skip)|- pushIfAway(user, title, body)|- broadcastPresence(user, online)", FILL);
        cls("handler", 40, 260, 300, "inner class", "ConnectionHandler", "- socket: Socket|- userId, token: String", "+ run()|- route(frame)|- sendMessage / setStatus / ...", FILL);
        cls("gateway", 670, 50, 250, "", "WebGateway", "", "~ serve(in, out, media, max)|~ isHttp(in)|record Upgrade(input, output, ...)", FILL);
        cls("wsin", 670, 190, 120, "", "WsInput", "- inflater", "+ read(...)", FILL);
        cls("wsout", 800, 190, 120, "", "WsOutput", "- deflater", "+ flush()", FILL);
        cls("push", 400, 50, 230, "", "WebPush", "- vapidPrivate, vapidPublic", "~ send(subscription, json)|~ encrypt(key, auth, text)", FILL);
        cls("services", 400, 268, 230, "", "Services", "AuthService|GroupService|SocialFeedService", "", FILL);
        cls("engine", 430, 380, 280, "interface", "StorageEngine", "", "+ appendMessage(Message)|+ getChangesSince(user, rev)|+ savePushSubscription(...)|+ flush() / compact() / close()", IFACE);
        cls("mem", 300, 600, 250, "", "InMemoryStorageEngine", "- messages: List<Message>|- revisions, tombstones", "+ currentRevision()", FILL);
        cls("wal", 600, 600, 260, "", "WalStorageEngine", "- channel: FileChannel|- state: InMemoryStorageEngine|- replica: StorageReplica", "- appendRecord(type, fields)|- compactNow()", FILL);
        cls("replica", 900, 380, 250, "interface", "StorageReplica", "", "+ restore(snapshot, wal)|+ appendLog(bytes)|+ replaceSnapshot(bytes)", IFACE);
        cls("bsr", 900, 560, 250, "", "BlobStoreReplica", "- uploader: ScheduledExecutor", "- seal / open  (AES-256-GCM)|- putBlob / getBlob", FILL);
        cls("blob", 900, 730, 250, "interface", "BlobStore", "", "+ list / put / get / delete", IFACE);
        cls("rootz", 600, 790, 230, "", "RootzBlobStore", "- http: HttpClient", "~ findSubfolder(key, parent, name)", FILL);
        cls("ex", 60, 690, 200, "", "java.lang.Thread", "", "", new Color(0xF2F2F2));
        cls("auto", 20, 800, 180, "", "AutoSaveThread", "", "+ run(): flush + compact", FILL);
        cls("reaper", 215, 800, 180, "", "ExpiryReaperThread", "", "+ run(): purge expired", FILL);
        edge("server", "handler", Kind.COMPOSE, "1 .. *");
        edgeAt(340, 280, 670, 150, Kind.DEPEND, "HTTP / upgrade");
        edge("gateway", "wsin", Kind.COMPOSE, "");
        edge("gateway", "wsout", Kind.COMPOSE, "");
        edge("server", "push", Kind.ASSOC, "");
        edge("handler", "services", Kind.ASSOC, "uses");
        edge("handler", "engine", Kind.ASSOC, "reads / writes");
        edge("mem", "engine", Kind.REALIZE, "");
        edge("wal", "engine", Kind.REALIZE, "");
        edge("wal", "mem", Kind.COMPOSE, "state");
        edge("wal", "replica", Kind.ASSOC, "0..1");
        edge("bsr", "replica", Kind.REALIZE, "");
        edge("bsr", "blob", Kind.ASSOC, "data + media");
        edge("rootz", "blob", Kind.REALIZE, "");
        edge("auto", "ex", Kind.INHERIT, "");
        edge("reaper", "ex", Kind.INHERIT, "");
        save(img, dir + "/fig3-server-classes.png");
    }

    static void clientClasses(String dir) throws Exception {
        int W = 1220, H = 880;
        BufferedImage img = canvas(W, H);
        title("Figure 4 · Client classes, transports and the exception hierarchy", W);
        cls("ctrl", 40, 50, 300, "", "ClientController", "- link: ServerLink|- model: ClientStateModel", "+ login / register(...)|+ sendMessage(conv, body)|+ pinMessage / starMessage(...)|+ transport(): String", FILL);
        cls("link", 420, 50, 250, "interface", "ServerLink", "", "+ input() / output()|+ isClosed()|+ describe()", IFACE);
        cls("tls", 380, 220, 150, "", "TlsLink", "- socket: SSLSocket", "", FILL);
        cls("ws", 560, 220, 170, "", "WebSocketLink", "- socket: WebSocket", "+ onBinary(...)", FILL);
        cls("thread", 140, 510, 180, "", "java.lang.Thread", "", "", new Color(0xF2F2F2));
        cls("out", 40, 400, 180, "", "OutboundSenderThread", "- queue: LinkedList", "+ enqueue(frame)", FILL);
        cls("in", 240, 400, 180, "", "InboundReceiverThread", "", "+ run()", FILL);
        cls("model", 780, 50, 230, "", "ClientStateModel", "- conversations: Map", "+ addMessage(Message)|- fire(event, message)", FILL);
        cls("listener", 780, 230, 230, "interface", "ClientModelListener", "", "+ onModelChanged(...)", IFACE);
        cls("jpanel", 1040, 230, 120, "", "JPanel", "", "", new Color(0xF2F2F2));
        cls("conv", 780, 360, 200, "", "ConversationPanel", "", "", FILL);
        cls("list", 1000, 360, 160, "", "ChatListPanel", "", "", FILL);
        cls("hasher", 780, 470, 210, "interface", "PasswordHasher", "", "+ hash / verify", IFACE);
        cls("pbkdf", 780, 570, 210, "", "Pbkdf2PasswordHasher", "", "", FILL);
        cls("exc", 40, 610, 170, "", "java.lang.Exception", "", "", new Color(0xF2F2F2));
        cls("ember", 260, 610, 170, "", "EmberException", "", "", EXC);
        double ex = 40;
        String[] subs = {"ValidationException", "AuthorizationException", "AuthenticationException", "ResourceNotFoundException", "RateLimitedException", "MediaValidationException"};
        for (String s : subs) {
            cls(s, ex, 720, 190, "", s, "", "", EXC);
            edgeAt(ex + 95, 720, 345, 610 + boxes.get("ember").h(), Kind.INHERIT, "");
            ex += 196;
        }
        cls("locked", 432, 810, 230, "", "AuthenticationLockedException", "", "", EXC);
        edge("ctrl", "link", Kind.ASSOC, "uses");
        edge("tls", "link", Kind.REALIZE, "");
        edge("ws", "link", Kind.REALIZE, "");
        edgeAt(120, 50 + boxes.get("ctrl").h(), 120, 400, Kind.ASSOC, "writes frames");
        edgeAt(300, 50 + boxes.get("ctrl").h(), 320, 400, Kind.ASSOC, "reads events");
        edgeAt(130, 400 + boxes.get("out").h(), 200, 510, Kind.INHERIT, "");
        edgeAt(330, 400 + boxes.get("in").h(), 260, 510, Kind.INHERIT, "");
        edge("model", "listener", Kind.ASSOC, "notifies (Observer)");
        edge("conv", "listener", Kind.REALIZE, "");
        edge("list", "listener", Kind.REALIZE, "");
        edgeAt(1080, 360, 1100, 230 + boxes.get("jpanel").h(), Kind.INHERIT, "");
        edge("pbkdf", "hasher", Kind.REALIZE, "");
        center("Strategy pattern", LABEL, 940, 560);
        edge("ember", "exc", Kind.INHERIT, "");
        edge("locked", "AuthenticationException", Kind.INHERIT, "");
        save(img, dir + "/fig4-client-classes.png");
    }

    static void sequence(String dir) throws Exception {
        String[] who = {"Alice (website)", "ConnectionHandler", "StorageEngine", "BlobStoreReplica", "rootz.so", "Bob's online device", "WebPush", "Push service"};
        int W = 1240, H = 700;
        BufferedImage img = canvas(W, H);
        title("Figure 5 · Send → deliver → read, with off-server copy and push", W);
        double gap = (W - 180) / (double) (who.length - 1);
        double[] x = new double[who.length];
        for (int i = 0; i < who.length; i++) {
            x[i] = 90 + i * gap;
            g.setFont(NAME);
            int w = g.getFontMetrics().stringWidth(who[i]) + 16;
            g.setColor(FILL);
            g.fill(new Rectangle2D.Double(x[i] - w / 2.0, 50, w, 28));
            g.setColor(INK);
            g.draw(new Rectangle2D.Double(x[i] - w / 2.0, 50, w, 28));
            center(who[i], NAME, x[i], 69);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {4, 4}, 0));
            g.draw(new Line2D.Double(x[i], 78, x[i], H - 20));
            g.setStroke(new BasicStroke(1.1f));
        }
        Object[][] steps = {
            {0, 1, "CMD_MSG_SEND (WSS frame)", false}, {1, 2, "appendMessage(): seq + revision", false},
            {2, 3, "appendLog(record)", false}, {1, 0, "EVT_MSG_ACK (sent)", true},
            {1, 5, "EVT_MSG_DELIVER (every device)", true}, {5, 1, "CMD_MSG_RECEIPT READ", false},
            {1, 0, "EVT_RECEIPT_UPDATE (read)", true}, {1, 6, "pushIfAway(offline recipient)", false},
            {6, 7, "POST aes128gcm + VAPID", false}, {7, 7, "notification: Reply / Mark read", true},
            {3, 4, "every 2 s: encrypted batch upload", false}};
        double y = 115;
        int n = 1;
        for (Object[] s : steps) {
            int a = (int) s[0], b = (int) s[1];
            boolean reply = (boolean) s[3];
            if (a == b) {
                g.setColor(NOTE);
                g.fill(new RoundRectangle2D.Double(x[a] - 130, y - 14, 125, 34, 8, 8));
                g.setColor(INK);
                g.draw(new RoundRectangle2D.Double(x[a] - 130, y - 14, 125, 34, 8, 8));
                g.setFont(LABEL);
                g.drawString(n++ + ". shows notification", (float) (x[a] - 124), (float) (y));
                g.drawString("Reply / Mark read", (float) (x[a] - 124), (float) (y + 13));
            } else {
                line(new Point2D.Double(x[a], y), new Point2D.Double(x[b], y), reply ? Kind.DEPEND : Kind.ASSOC, null);
                g.setFont(LABEL);
                g.setColor(new Color(0x333366));
                String text = n++ + ". " + s[2];
                double tx = Math.min(x[a], x[b]) + 8;
                g.drawString(text, (float) tx, (float) (y - 5));
            }
            y += 50;
        }
        save(img, dir + "/fig5-sequence.png");
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        new File(dir).mkdirs();
        useCase(dir);
        deployment(dir);
        serverClasses(dir);
        clientClasses(dir);
        sequence(dir);
    }
}
