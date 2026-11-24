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
            {"call", "Voice / video call, move it to the website"},
            {"admin", "Review reports, unlock accounts"}};
        Map<String, double[]> pos = new HashMap<>();
        double y = 110;
        for (String[] c : cases) { pos.put(c[0], ellipse(430, y, 170, 24, c[1], FILL)); y += 60; }
        double[] store = ellipse(780, 110 + 60, 110, 26, "Keep data off-server", IFACE);
        double[] authz = ellipse(780, 110 + 60 * 4.5, 110, 26, "Authorize object access", IFACE);
        actor(95, 300, "Student");
        actor(95, 600, "Administrator");
        actor(1040, 160, "rootz.so");
        actor(1040, 110 + 60 * 7 - 20, "Push service");
        for (String c : List.of("auth", "msg", "react", "group", "pulse", "social", "status", "notify", "call")) {
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
        for (String c : List.of("react", "group", "pulse", "social", "call")) {
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
        node(430, 160, 330, 260, "Render free web service (Docker)", "EmberServer (web-only, --no-tls)|WebGateway: files, /media, /health, WSS|ConnectionHandler per device|WebPush (RFC 8291 + VAPID)|WalStorageEngine + local cache disk|BlobStoreReplica (batch every 2 s)|JRE 21, ~64 MB RAM idle (measured locally)");
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
        Object[][] steps = {
            {0, 1, "CMD_MSG_SEND (WSS frame)", false}, {1, 2, "appendMessage(): seq + revision", false},
            {2, 3, "appendLog(record)", false}, {1, 0, "EVT_MSG_ACK (sent)", true},
            {1, 5, "EVT_MSG_DELIVER (every device)", true}, {5, 1, "CMD_MSG_RECEIPT READ", false},
            {1, 0, "EVT_RECEIPT_UPDATE (read)", true}, {1, 6, "pushIfAway(offline recipient)", false},
            {6, 7, "POST aes128gcm + VAPID", false}, {7, 7, "shows notification|Reply / Mark read", true},
            {3, 4, "every 2 s: encrypted batch upload", false}};
        sequenceDiagram(who, steps, 1240, 700, 50, dir + "/fig5-sequence.png");
    }

    /** A sequence diagram: lifelines, then numbered messages (a self-message is drawn as a note). */
    static void sequenceDiagram(String[] who, Object[][] steps, int W, int H, double step, String file) throws Exception {
        BufferedImage img = canvas(W, H);
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
        double y = 115;
        int n = 1;
        for (Object[] s : steps) {
            int a = (int) s[0], b = (int) s[1];
            boolean reply = (boolean) s[3];
            if (a == b) {
                String[] note = ((String) s[2]).split("\\|");
                g.setFont(LABEL);
                double nw = 0;
                for (String t : note) nw = Math.max(nw, g.getFontMetrics().stringWidth(t) + 26);
                double nx = x[a] + 8 + nw > W - 4 ? x[a] - 8 - nw : x[a] + 8;
                g.setColor(NOTE);
                g.fill(new RoundRectangle2D.Double(nx, y - 14, nw, 8 + 13 * note.length, 8, 8));
                g.setColor(INK);
                g.draw(new RoundRectangle2D.Double(nx, y - 14, nw, 8 + 13 * note.length, 8, 8));
                for (int i = 0; i < note.length; i++) {
                    g.drawString((i == 0 ? n++ + ". " : "    ") + note[i], (float) (nx + 6), (float) (y + i * 13));
                }
            } else {
                line(new Point2D.Double(x[a], y), new Point2D.Double(x[b], y), reply ? Kind.DEPEND : Kind.ASSOC, null);
                g.setFont(LABEL);
                g.setColor(new Color(0x333366));
                String text = n++ + ". " + s[2];
                double tx = Math.min(x[a], x[b]) + 8;
                g.drawString(text, (float) tx, (float) (y - 5));
            }
            y += step;
        }
        save(img, file);
    }

    static void callSequence(String dir) throws Exception {
        String[] who = {"Alice (website)", "EmberServer + CallRegistry", "Bob (desktop app)", "Bob (website tab)"};
        Object[][] steps = {
            {0, 1, "CALL ring (dm:alice:bob)", false}, {1, 2, "ring", true},
            {2, 1, "accept", false}, {1, 0, "joined (bob)", true},
            {0, 2, "key: fresh ECDH P-256 public half (the server only forwards it)", false},
            {2, 0, "relay info sealed with the pair key: sender key, codecs [4, 3, 2], v: 2", false},
            {0, 2, "voice packets, sender-key encrypted; the server stamps the sender", false},
            {2, 2, "Video on website|opens ?join=<callId>", false},
            {3, 1, "handoff-prepare (callId)", false}, {1, 3, "handoff-ready: single-use ticket, 30 s", true},
            {3, 3, "Join call clicked|camera + mic allowed", false},
            {3, 1, "handoff-commit (ticket): first commit wins", false}, {1, 3, "roster (alice)", true},
            {1, 2, "end, reason = elsewhere", true}, {1, 0, "moved (bob)", true},
            {0, 3, "new pair key + new sender key; new safety code on both screens", false},
            {3, 0, "voice + video over the relay (ladder 180p to 720p)", false}};
        sequenceDiagram(who, steps, 1180, 760, 37, dir + "/fig6-call-sequence.png");
    }

    static void state(double x, double y, double w, String name, String sub) {
        double h = sub.isEmpty() ? 34 : 46;
        g.setColor(FILL);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 18, 18));
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.3f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 18, 18));
        center(name, NAME, x + w / 2, y + 21);
        if (!sub.isEmpty()) center(sub, LABEL, x + w / 2, y + 36);
    }

    static void dot(double x, double y, boolean end) {
        g.setColor(INK);
        if (end) {
            g.draw(new Ellipse2D.Double(x - 10, y - 10, 20, 20));
            g.fill(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
        } else {
            g.fill(new Ellipse2D.Double(x - 8, y - 8, 16, 16));
        }
    }

    static void callStates(String dir) throws Exception {
        int W = 1180, H = 640;
        BufferedImage img = canvas(W, H);
        g.setColor(INK);
        center("Call lifecycle (desktop CallSession.State)", NAME, 300, 30);
        dot(300, 70, false);
        state(60, 120, 180, "RINGING_OUT", "caller, ringing");
        state(360, 120, 180, "RINGING_IN", "callee, ringing");
        state(210, 260, 180, "CONNECTING", "pair keys, relay info");
        state(210, 400, 180, "ACTIVE", "voice flows");
        state(420, 530, 140, "ENDED", "");
        edgeAt(292, 76, 160, 120, Kind.ASSOC, "ring");
        edgeAt(308, 76, 450, 120, Kind.ASSOC, "incoming ring");
        edgeAt(150, 166, 260, 260, Kind.ASSOC, "joined");
        edgeAt(450, 166, 340, 260, Kind.ASSOC, "accept");
        edgeAt(300, 306, 300, 400, Kind.ASSOC, "first voice frame");
        edgeAt(330, 446, 440, 532, Kind.ASSOC, "hang up / end / elsewhere");
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new Line2D.Double(70, 166, 70, 547));
        edgeAt(70, 547, 420, 547, Kind.ASSOC, "no answer (45 s)");
        edgeAt(520, 166, 520, 530, Kind.ASSOC, "declined");
        dot(490, 610, true);
        edgeAt(490, 564, 490, 598, Kind.ASSOC, "");
        g.setFont(ITAL);
        g.setColor(INK);
        g.drawString("on moved: re-key,", 88f, 420f);
        g.drawString("stays ACTIVE", 88f, 434f);

        double lx = 720;
        center("Relay video ladder (quality.js Ladder, checked every second)", NAME, 920, 30);
        String[][] rungs = {{"720p / 30 fps", "1800 kbit/s"}, {"540p / 30 fps", "1100 kbit/s"}, {"360p / 25 fps", "600 kbit/s (start)"},
                {"270p / 20 fps", "300 kbit/s"}, {"180p / 15 fps", "150 kbit/s (floor)"}};
        for (int i = 0; i < rungs.length; i++) {
            state(lx, 60 + i * 100, 200, rungs[i][0], rungs[i][1]);
            if (i > 0) {
                edgeAt(lx + 55, 60 + i * 100, lx + 55, 60 + (i - 1) * 100 + 46, Kind.ASSOC, "6 s calm");
                edgeAt(lx + 145, 60 + (i - 1) * 100 + 46, lx + 145, 60 + i * 100, Kind.ASSOC, "trouble");
            }
        }
        dot(lx - 40, 283, false);
        edgeAt(lx - 32, 283, lx, 283, Kind.ASSOC, "");
        g.setFont(BODY);
        g.setColor(INK);
        String[] notes = {"Trouble = frames skipped for a backed-up", "socket, round trip up > 150 ms, or", "> 10 % of frames late or lost.", "",
            "Down: at once, from the rung in use.", "Up: one rung after 6 s without trouble.", "A rung that failed waits 12 s, 24 s,", "... up to 60 s before another try.", "",
            "Never above min(camera, mode,", "the other side's display)."};
        for (int i = 0; i < notes.length; i++) g.drawString(notes[i], (float) (lx + 225), (float) (80 + i * 17));
        save(img, dir + "/fig7-call-states.png");
    }

    static void crc(double x, double y, double w, double h, String name, String[] resp, String[] collab) {
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(x, y, w, h));
        g.setColor(FILL);
        g.fill(new Rectangle2D.Double(x, y, w, 26));
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.3f));
        g.draw(new Rectangle2D.Double(x, y, w, h));
        center(name, NAME, x + w / 2, y + 18);
        g.draw(new Line2D.Double(x, y + 26, x + w, y + 26));
        double split = x + w * 0.6;
        g.draw(new Line2D.Double(split, y + 26, split, y + h));
        g.setFont(ITAL);
        g.drawString("Responsibilities", (float) (x + 6), (float) (y + 41));
        g.drawString("Collaborators", (float) (split + 6), (float) (y + 41));
        g.setFont(BODY);
        for (int i = 0; i < resp.length; i++) g.drawString(resp[i], (float) (x + 6), (float) (y + 58 + i * 15));
        for (int i = 0; i < collab.length; i++) g.drawString(collab[i], (float) (split + 6), (float) (y + 58 + i * 15));
    }

    static void callCrc(String dir) throws Exception {
        int W = 1180, H = 360;
        BufferedImage img = canvas(W, H);
        double w = 280, h = 148, gx = 12, gy = 14;
        Object[][] cards = {
            {"CallSession (desktop)", new String[] {"ring, accept, end", "pair keys, sender key", "safety codes", "hand-off link; re-key on moved"}, new String[] {"ClientController", "VoiceEngine", "E2eCrypto", "CallDialog"}},
            {"VoiceEngine", new String[] {"capture, clean, resample", "encode, send 20 ms frames", "mix everyone's voice", "reopen a lost device"}, new String[] {"AudioDevices", "AudioCodec", "JitterBuffer", "Resampler", "Transport"}},
            {"JitterBuffer", new String[] {"order frames by sequence", "adapt delay 40-200 ms", "conceal a missing frame", "trim a backlog (25 max)"}, new String[] {"AudioFrame", "Clock", "VoiceEngine"}},
            {"AudioCodec (interface)", new String[] {"encode / decode a frame", "sample rate, frame size", "fresh copy per call"}, new String[] {"AdpcmCodec", "MulawCodec", "CodecFactory"}},
            {"CodecFactory", new String[] {"choose the send codec:", "my preference that the", "other side can decode"}, new String[] {"QualityPolicy", "AudioCodec"}},
            {"QualityPolicy (abstract)", new String[] {"per mode: voice rate,", "Opus bitrate, video ceiling,", "follow the display or not"}, new String[] {"DataSaver", "Auto, Max", "QualityMode", "CodecFactory"}},
            {"CallRegistry (server)", new String[] {"who is in which call", "ring timeout, one call each", "hand-off tickets (30 s)", "media and signal limits"}, new String[] {"EmberServer", "TokenBucket"}},
            {"Ladder (website)", new String[] {"network estimate each second", "step down at once", "step up after calm", "back off failed rungs"}, new String[] {"relayLimit", "call.js"}}};
        for (int i = 0; i < cards.length; i++) {
            double x = 20 + (i % 4) * (w + gx), y = 20 + (i / 4) * (h + gy);
            crc(x, y, w, h, (String) cards[i][0], (String[]) cards[i][1], (String[]) cards[i][2]);
        }
        save(img, dir + "/fig8-call-crc.png");
    }


    public static void main(String[] args) throws Exception {
        String dir = args[0];
        new File(dir).mkdirs();
        useCase(dir);
        deployment(dir);
        serverClasses(dir);
        clientClasses(dir);
        sequence(dir);
        callSequence(dir);
        callStates(dir);
        callCrc(dir);
    }
}
