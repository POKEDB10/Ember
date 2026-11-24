// Builds docs/report/Ember_Report.docx to the CS F213 report rules:
// Times New Roman, 12 pt text, 14 pt headings, 1.15 line spacing, diagrams and screenshots.
// Run from this folder: npm install, then node report.js. export_pdf.py makes the PDF.
const fs = require('fs');
const path = require('path');
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, ImageRun, Table, TableRow, TableCell,
  WidthType, ShadingType, BorderStyle, Footer, Header, PageNumber, LevelFormat, TableOfContents, TabStopType,
  VerticalAlign, PageOrientation,
} = require('docx');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const OUT = path.join(ROOT, 'docs', 'report', 'Ember_Report.docx');
const FONT = 'Times New Roman';
const ACCENT = '8A3A12'; // dark rust: reads as near-black on a grey-scale printer
const RULE = 'BFA88C';
const PAGE_W = 11906, PAGE_H = 16838; // A4
const M_LEFT = 1700, M_RIGHT = 1300, M_TOP = 1300, M_BOTTOM = 1300; // a little extra on the binding side
const TEXT_W = PAGE_W - M_LEFT - M_RIGHT;
const MAX_IMG_PX = Math.floor(TEXT_W / 15); // twips to pixels at 96 dpi
const L_MARGIN = 1000, L_TEXT_W = PAGE_H - 2 * L_MARGIN; // landscape pages for the UML figures

// ---------- helpers ----------
const run = (text, o = {}) => new TextRun({ text, font: FONT, ...o });
const rich = (parts) => parts.map((x) => (typeof x === 'string' ? run(x) : run(x[0], x[1])));
const p = (text, o = {}) => new Paragraph({ children: typeof text === 'string' ? [run(text)] : rich(text),
  spacing: { after: 140 }, alignment: AlignmentType.JUSTIFIED, ...o });
const lead = (title, text) => p([[title + ' ', { bold: true }], text]); // run-in heading inside body text
const h1 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_1, pageBreakBefore: true, children: [run(text)],
  border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: ACCENT, space: 4 } } });
const h2 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [run(text)] });
const bullet = (text) => new Paragraph({ numbering: { reference: 'bullets', level: 0 }, spacing: { after: 80 }, children: typeof text === 'string' ? [run(text)] : rich(text) });
const code = (lines) => lines.split('\n').map((line, i, all) => new Paragraph({
  spacing: { after: i === all.length - 1 ? 200 : 0, before: i === 0 ? 60 : 0, line: 240 },
  shading: { type: ShadingType.CLEAR, fill: 'F6F2EC', color: 'auto' },
  border: { left: { style: BorderStyle.SINGLE, size: 18, color: ACCENT, space: 8 } },
  indent: { left: 240, right: 120 }, keepLines: true, keepNext: i < all.length - 1,
  children: [new TextRun({ text: line || ' ', font: 'Consolas', size: 19 })],
}));

function imageSize(file) {
  const d = fs.readFileSync(file);
  if (d[0] === 0x89) return [d.readUInt32BE(16), d.readUInt32BE(20)];
  for (let i = 2; i < d.length;) { // JPEG: walk the segments to the frame header
    const marker = d[i + 1], len = d.readUInt16BE(i + 2);
    if (marker >= 0xC0 && marker <= 0xC3) return [d.readUInt16BE(i + 7), d.readUInt16BE(i + 5)];
    i += 2 + len;
  }
  throw new Error('cannot read image size: ' + file);
}
let figureNo = 0;
function figure(file, caption, maxWidth = MAX_IMG_PX, maxHeight = 10000) {
  const full = path.join(ROOT, file);
  const [w, h] = imageSize(full);
  const width = Math.round(Math.min(maxWidth, w, maxHeight * w / h)), height = Math.round(h * width / w);
  figureNo++;
  return [
    new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 160, after: 80 }, keepNext: true,
      children: [new ImageRun({ type: file.endsWith('.png') ? 'png' : 'jpg', data: fs.readFileSync(full),
        transformation: { width, height }, altText: { title: 'Figure ' + figureNo, description: caption, name: 'figure' + figureNo } })] }),
    new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 280 },
      children: [run('Figure ' + figureNo + '. ', { bold: true }), run(caption, { italics: true })] }),
  ];
}

// a diagram gets its own landscape page so its labels stay readable in print
const wide = (file, caption) => ({ wide: figure(file, caption, Math.floor(L_TEXT_W / 15), 560) });
let tableNo = 0;
const NONE = { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' };
function table(headers, rows, widths, caption, { grid = false, center = [] } = {}) {
  const total = widths.reduce((a, c) => a + c, 0);
  const scaled = widths.map((x) => Math.round(x * TEXT_W / total));
  scaled[scaled.length - 1] += TEXT_W - scaled.reduce((a, c) => a + c, 0);
  const line = (size, color) => ({ style: BorderStyle.SINGLE, size, color });
  const borders = grid
    ? { top: line(6, ACCENT), bottom: line(6, ACCENT), left: line(4, RULE), right: line(4, RULE), insideHorizontal: line(4, RULE), insideVertical: line(4, RULE) }
    : { top: line(12, ACCENT), bottom: line(12, ACCENT), left: NONE, right: NONE, insideHorizontal: line(4, RULE), insideVertical: NONE };
  const cell = (text, i, head) => new TableCell({ width: { size: scaled[i], type: WidthType.DXA }, verticalAlign: VerticalAlign.CENTER,
    shading: head ? { type: ShadingType.CLEAR, fill: 'F1E6D6', color: 'auto' } : undefined,
    borders: head ? { bottom: line(8, ACCENT) } : undefined,
    margins: { top: 70, bottom: 70, left: 110, right: 110 },
    children: [new Paragraph({ spacing: { after: 0, line: 252 }, alignment: center.includes(i) ? AlignmentType.CENTER : AlignmentType.LEFT,
      children: [run(text, { bold: head })] })] });
  tableNo++;
  return [
    new Paragraph({ spacing: { before: 160, after: 80 }, keepNext: true,
      children: [run('Table ' + tableNo + '. ', { bold: true }), run(caption, { italics: true })] }),
    new Table({ width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: scaled, borders,
      rows: [new TableRow({ tableHeader: true, children: headers.map((x, i) => cell(x, i, true)) }),
        ...rows.map((r) => new TableRow({ cantSplit: true, children: r.map((x, i) => cell(x, i, false)) }))] }),
    new Paragraph({ spacing: { after: 160 }, children: [] }),
  ];
}

// ---------- cover page ----------
const center = (text, o = {}, after = 120, before = 0) => new Paragraph({ alignment: AlignmentType.CENTER,
  spacing: { before, after }, children: [run(text, o)] });
const rule = (after = 200) => new Paragraph({ spacing: { after }, children: [],
  border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: ACCENT, space: 1 } }, indent: { left: 1800, right: 1800 } });
const cover = [
  center('BITS PILANI, DUBAI CAMPUS', { bold: true, size: 28, characterSpacing: 40 }, 60),
  center('CS F213  Object Oriented Programming', { size: 24 }, 1100),
  rule(300),
  center('EMBER', { bold: true, size: 96, color: ACCENT, characterSpacing: 200 }, 80),
  center('Say it. Spark it. Let it fade.', { italics: true, size: 28 }, 200),
  center('A multi-device messenger and campus social network in Java', { size: 26 }, 300),
  rule(900),
  center('PROJECT REPORT', { bold: true, size: 28, characterSpacing: 60 }, 240),
  ...table(['No.', 'Name of team member', 'Roll number'], Array.from({ length: 6 }, (_, i) => [String(i + 1), '', '']),
    [8, 56, 36], 'Team members', { grid: true, center: [0] }).slice(1, 2),
  center('', {}, 500),
  center('Course: CS F213 Object Oriented Programming', { size: 24 }, 60),
  center('Semester: First Semester 2026-27', { size: 24 }, 60),
  center('Submission date: 30 November 2026', { size: 24, bold: true }, 0),
];
tableNo = 0; // the cover table is not numbered

// ---------- body ----------
const tests = [
  ['Protocol and passwords', 'Payload encoding round trip, wrong magic number and oversized frames rejected, PBKDF2 hashing, input validation, a real TLS 1.3 round trip.', '6'],
  ['Messaging', 'Register, send and deliver; offline messages arrive once after reconnecting; the local outbox; group receipts; replies and reactions; 15-second messages; in-chat search (including Arabic).', '7'],
  ['Website and devices', 'Two devices on one account; the web-only server and the desktop app over WebSocket; compressed WebSocket traffic; cached image downloads; syncing only changes; Web Push encryption and its safety checks.', '5'],
  ['Storage and recovery', 'Log replay after restart, damaged log tails, compressed snapshots, group roles after restart, and rebuilding a wiped server from the encrypted off-server copy (a wrong key is refused).', '6'],
  ['Abuse protection', 'Lockout after five bad logins, at most four password checks at once, message flood limit, loopback limiter, fake image files rejected before decoding.', '5'],
  ['Social and admin', 'Campus Pulse posts, likes and comments; private profiles and follow requests; blocking and reports; stories that expire; the admin report queue; the demo data seed.', '6'],
  ['Desktop interface', 'Theme palettes, wrapped message bubbles, notifications on the Swing event thread.', '1'],
  ['End-to-end chats', 'Tampered text, an impostor device and an outsider are refused; the server only ever sees ciphertext.', '1'],
  ['Calls', 'Ringing, answering, the encrypted relay, busy and blocked callers; voice codecs and shared test vectors; the resampler; the jitter buffer; settings and a microphone unplugged mid-call; the feature flag; hand-off races, reuse and expiry; Speaker mode.', '9'],
];

const body = [
  new Paragraph({ spacing: { after: 240 }, children: [run('Contents', { bold: true, size: 28, color: ACCENT })],
    border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: ACCENT, space: 4 } } }),
  new TableOfContents('Contents', { hyperlink: true, headingStyleRange: '1-2' }),

  h1('Abstract'),
  p(`Ember is a chat and campus social app we built in Java for CS F213. It has two front ends, a website that installs like a phone app and a Swing desktop program, both connected to one server. One account can be logged in on several devices at once, and every device shows the same chats live. Users can chat one-to-one or in groups, react to, edit, pin and star messages, run polls, share photos and stories, and follow or block other students. Message text is end-to-end encrypted, so the server stores only ciphertext. Users can also make one-to-one and group voice and video calls, which are end-to-end encrypted too and fall back to a relay inside the website's own HTTPS connection where calling apps are blocked. A one-to-one call can move from the desktop app to the website to add video. The server uses its own binary protocol, hashes passwords with PBKDF2 and logs every change to a file that is encrypted and copied to cloud storage, so the free host can restart without losing data. The code applies inheritance, abstract classes, interfaces, exceptions, threads, file handling, collections, generics and Swing.`),
  p([['Keywords: ', { bold: true }], 'Java, object-oriented design, Swing, sockets, WebSocket, multithreading, write-ahead log, end-to-end encryption, WebRTC, design patterns.'], { alignment: AlignmentType.LEFT }),

  h1('1. Introduction'),
  h2('1.1 Background and motivation'),
  p('Most of us run our studies through three or four apps at once: one for the class group, another for lab partners, and a social app to see what is going on around campus. We also move between a phone and a laptop several times a day. When we had to pick a topic for the OOP project, we wanted to build something we would actually use, and something large enough that the course topics would come up on their own instead of being forced in.'),
  p('A messenger turned out to be a good fit. It needs a clear class design (users, messages, groups, posts). Many things happen at the same time, so it needs threads. Networks fail and users type strange input, so exception handling matters. Messages must survive a restart, which means file handling, and of course it needs a GUI. It also gave us a reason to find out how real chat apps work underneath, which we had always been curious about.'),
  p('The project grew in stages. We started with a desktop messenger that ran on one laptop, then added the Campus Pulse feed, and then a website so the same account could be used from a phone. The last stage was hosting. For the demo we did not want to depend on one of our laptops staying switched on, and we did not want to pay for a server, so the data had to live somewhere safer than a free server\'s disk.'),
  h2('1.2 Objectives'),
  bullet([['One account, many devices. ', { bold: true }], 'Log in on a phone, a laptop and the desktop app at the same time and see the same messages on all three.']),
  bullet([['Two clients, one server. ', { bold: true }], 'A Swing desktop app and a website that installs as an app, both following exactly the same server rules.']),
  bullet([['Use the course properly. ', { bold: true }], 'Apply the CS F213 topics where they make sense, in code that every team member can explain in the viva.']),
  bullet([['Keep accounts safe. ', { bold: true }], 'Encrypted connections, hashed passwords, and a server that checks permission on every request.']),
  bullet([['Never lose data. ', { bold: true }], 'Survive crashes and restarts, including on a free host whose disk is wiped each time.']),
  bullet([['Stay light. ', { bold: true }], 'Fit in the 512 MB of memory a free cloud server gives you and use little mobile data.']),
  h2('1.3 Scope'),
  p(`Ember is written in Java 17 using only the JDK, with no outside libraries. The website is plain HTML, CSS and JavaScript using only what browsers provide. The project covers one-to-one and group chat with end-to-end encrypted message text, the Campus Pulse feed, profiles and moderation, statuses and notifications, one-to-one and group voice and video calls, the two clients, and free hosting (Render runs the server and rootz.so stores the encrypted data). Screen sharing, call recording, changing video formats on the server, native phone apps and attachments other than pictures are outside the scope of this version.`),

  h1('2. System Analysis and Design'),
  h2('2.1 Requirement analysis'),
  p('We wrote the requirements as short statements we could test. The functional requirements describe what a user can do; the non-functional ones describe how well the system has to do it.'),
  ...table(['ID', 'Functional requirement'], [
    ['F1', 'Register, log in and log out, and stay logged in on several devices at once.'],
    ['F2', 'Send and receive direct and group messages live, with sent, delivered and read ticks.'],
    ['F3', 'Reply to, react to, edit, delete, pin and star messages, and search inside a chat.'],
    ['F4', 'Send disappearing messages that delete themselves after 15 seconds.'],
    ['F5', 'Show a long message as a short preview with one "Read more" button.'],
    ['F6', 'Create groups, add members and run polls inside a group.'],
    ['F7', 'Choose a status: online, away, do not disturb or invisible. Show "idle" automatically after five minutes of no activity.'],
    ['F8', 'Notify the user about new messages and follow requests, with a quick reply, even when the website is closed.'],
    ['F9', 'Share photo posts and 24-hour stories, and like and comment on posts.'],
    ['F10', 'Edit a profile, make it private, follow people, approve or decline follow requests, block and report.'],
    ['F11', 'Let an administrator review reports and unlock locked accounts.'],
    ['F12', 'Offer several visual themes: seven on the website and six in the desktop app.'],
    ['F13', 'Encrypt message text end to end, so the server stores only ciphertext, and warn when a new device appears.'],
    ['F14', 'Make one-to-one and group voice and video calls from any chat; the desktop app joins with voice.'],
    ['F15', 'Let the user pick a call quality (Data saver, Auto, Max) and move a desktop voice call to the website to add video.'],
  ], [9, 91], 'Functional requirements', { center: [0] }),
  ...table(['Quality', 'What we required and how Ember meets it'], [
    ['Security', 'Every connection is encrypted (TLS 1.3 or HTTPS), and message text and calls are also end-to-end encrypted. Passwords are hashed with PBKDF2-HMAC-SHA256 at 600,000 iterations. The server checks permission on every request that names a message, group, post, profile or call. Repeated wrong passwords lock the account.'],
    ['Reliability', 'Every change goes into a log file with a checksum, so a crash loses nothing that was confirmed. An encrypted copy of new changes is uploaded off-server every 2 seconds.'],
    ['Performance', 'The server process uses about 64 MB of memory when idle (measured on a laptop with the same Java settings as the hosted server). Traffic is compressed, photos are shrunk before upload, and a returning browser downloads only what changed. Calls adapt their quality to the connection.'],
    ['Usability', 'Works on phone and desktop screens, can be used with the keyboard alone, has labels for screen readers and readable contrast in every theme.'],
    ['Portability', 'Plain Java 17 bytecode and standard web pages, so it runs on Windows, Linux and macOS and in Android and iOS browsers.'],
    ['Maintainability', 'Interfaces separate storage, transport, backup and audio codecs, and 46 Java tests plus 9 JavaScript tests run on every build.'],
  ], [20, 80], 'Non-functional requirements'),

  h2('2.2 Use case diagram'),
  p('Figures 1 to 5 are printed on landscape pages at the end of this chapter so that their labels stay readable. Figure 1 shows who uses the system. A student can use every messaging and social feature. An administrator is a student with two extra powers, reviewing reports and unlocking accounts, which the diagram shows with a generalisation arrow. Two outside systems take part: the browser push services that deliver notifications, and rootz.so, where the encrypted data is kept. Every use case that touches a stored object, including a call, includes the server\'s permission check.'),

  h2('2.3 Architecture and deployment'),
  p('All clients talk to a single server (Figure 2). A browser loads the website and then opens a WebSocket. The desktop app can use the same WebSocket when it is given the website address, or a direct TLS 1.3 socket on a local network. Either way the same binary frames travel over the connection, so the server applies one set of rules to every device.'),
  p('Online, the server runs in a Docker container on Render\'s free plan, behind Render\'s HTTPS proxy. Render wipes the container\'s disk whenever it restarts, so we treat that disk only as a cache. Every change is also saved, encrypted, in an "ember" folder on rootz.so, and a fresh container rebuilds itself from there when it starts. A free UptimeRobot monitor calls the /health address every five minutes so the service does not go to sleep.'),

  h2('2.4 Class diagrams'),
  p('The full program has about a hundred classes, which is too many for one readable picture, so we split the class diagram in two and show only the attributes and methods that matter for the design.'),
  p('Figure 3 is the server. EmberServer creates one ConnectionHandler (a member inner class) for each connected device, and keeps a map from every user to all of that user\'s open connections. This map is what makes multi-device delivery work. StorageEngine is an interface with two implementations. InMemoryStorageEngine keeps everything in collections and is used by the tests. WalStorageEngine writes each change to a log file and holds an InMemoryStorageEngine inside it for fast reads (composition). A WalStorageEngine can also be given a StorageReplica, which is the Strategy pattern: BlobStoreReplica encrypts the data and uploads it through the BlobStore interface, which RootzBlobStore implements for rootz.so. AutoSaveThread and ExpiryReaperThread both extend Thread.'),
  p('Figure 4 is the client side. ClientController only knows the ServerLink interface, so the same Swing app works over a TLS socket (TlsLink) or a WebSocket (WebSocketLink) and the choice is made at run time. Two Thread subclasses move frames in and out. ClientStateModel tells the Swing panels about changes through the ClientModelListener interface (the Observer pattern), and the panels themselves extend JPanel. The lower part of the figure shows our checked exception hierarchy, which starts at EmberException, and the PasswordHasher strategy.'),

  h2('2.5 Sequence diagram'),
  p('Figure 5 follows a single message from Alice to Bob. Alice\'s browser sends a CMD_MSG_SEND frame. The connection handler stores the message, which gives it a sequence number, replies to Alice with an acknowledgement, and then delivers it to every device Bob has online. When Bob opens the chat, his device sends back a READ receipt and Alice sees a double tick. If Bob has no device online and is not on Do Not Disturb, the server instead sends an encrypted push notification to each browser Bob has allowed. Separately, every two seconds the backup thread uploads the newest log records to rootz.so as one compressed, encrypted batch.'),

  h2('2.6 Wire protocol'),
  p('The client and server exchange binary frames that start with their own length. Each frame has an 18-byte header (Table 3) followed by the payload, which is a list of key=value lines in UTF-8. The server reads the length first and refuses anything too big before it allocates memory: 4 KB before login, and 25 MB by default after login (set with --max-frame-mb). We never use Java serialization, because deserializing data from strangers is a well-known way to attack Java programs.'),
  ...table(['Bytes', 'Field', 'Meaning'], [
    ['0 to 3', 'magic', '0x454D4252, the letters "EMBR". Any other value closes the connection.'],
    ['4', 'version', 'Protocol version, currently 1.'],
    ['5', 'opcode', 'The command or event, for example 0x10 for send and 0x43 for deliver.'],
    ['6 to 13', 'correlation id', 'Lets the client match a reply to the request that caused it.'],
    ['14 to 17', 'length', 'Size of the payload in bytes, checked before anything is read.'],
  ], [14, 22, 64], 'Frame header layout'),
  wide('docs/report/figures/fig1-use-case.png', 'Use case diagram.'),
  wide('docs/report/figures/fig2-deployment.png', 'Deployment diagram.'),
  wide('docs/report/figures/fig3-server-classes.png', 'Server and storage class diagram.'),
  wide('docs/report/figures/fig4-client-classes.png', 'Client, transport and exception class diagram.'),
  wide('docs/report/figures/fig5-sequence.png', 'Sequence diagram for sending, delivering and reading a message.'),

  h1('3. Implementation'),
  h2('3.1 Code modules'),
  p('The code is split into packages by job (Table 4). Code shared by the client and the server lives under edu.ember.common, so both sides use the very same protocol and model classes. Altogether there are 122 Java files with about 18,600 lines (tests included) and about 3,300 lines of website code.'),
  ...table(['Package', 'What it does', 'Main classes'], [
    ['common.protocol', 'Binary frames and payload encoding', 'WireFrame (Builder), FrameCodec, FrameFactory, KvPayloadCodec, WireOpcodes'],
    ['common.model', 'The data the app works with', 'Message, UserProfile, GroupChat, SocialPost, StoryItem, PollData, PushSubscription'],
    ['common.security', 'Validation, hashing, TLS', 'InputValidator, ImageValidator, PasswordHasher, Pbkdf2PasswordHasher, TlsSocketFactory'],
    ['common.exceptions', 'Our checked exceptions', 'EmberException and its seven subclasses'],
    ['server', 'Connections, rules, notifications', 'EmberServer, ConnectionHandler, WebGateway, WebPush, AuthService, GroupService, SocialFeedService'],
    ['server.storage', 'Saving, recovery and backup', 'StorageEngine, InMemoryStorageEngine, WalStorageEngine, BlobStoreReplica, RootzBlobStore'],
    ['client', 'Desktop networking and state', 'ClientController (Facade), ServerLink, ClientStateModel, sender and receiver threads'],
    ['client.ui', 'Swing screens', 'ChatWorkspacePanel, ConversationPanel, ChatBubblePanel, CampusPulsePanel, CallDialog, ThemeManager'],
    ['client.call', 'Desktop voice for calls', 'VoiceEngine, JitterBuffer, AudioCodec, AdpcmCodec, Resampler, QualityPolicy, CodecFactory, CallSettings'],
    ['tools', 'Demo and testing tools', 'AttackSimulator, ConcurrencyStressHarness, DemoSeeder, ReplicaCheck'],
    ['resources/web', 'The website', 'index.html, app.js, e2e.js (encryption), call.js and quality.js (calls), call-worklet.js (audio), sw.js'],
  ], [27, 25, 48], 'Packages (all under edu.ember)'),

  h2('3.2 Object-oriented concepts used'),
  p('Table 5 maps each course topic to the place in Ember where we used it. We tried to use a concept only where it solved a real problem; for example, the two storage engines exist because the tests need a fast store that never touches the disk.'),
  ...table(['Course topic', 'Where it appears in Ember'], [
    ['Classes, objects, constructors, static and final', 'Every model class; constants in WireOpcodes and AppInfo; overloaded constructors in Message and EmberServer.'],
    ['Encapsulation', 'Message keeps its fields private behind synchronized getters and setters such as pinned() and setPinned(). Stored data can only be reached through StorageEngine.'],
    ['Inheritance and overriding', 'Thread subclasses override run(); Swing panels extend JPanel; all our exceptions extend EmberException (including CallException and its subclasses); WsInput and WsOutput extend InputStream and OutputStream.'],
    ['Abstract classes', 'QualityPolicy declares what a call quality mode must answer (voice rate, Opus bitrate, video ceiling); DataSaver, Auto and Max fill it in.'],
    ['Interfaces and abstraction', 'StorageEngine, StorageReplica, BlobStore, ServerLink, PasswordHasher, ClientModelListener, AudioCodec, Clock, Transport, and the Microphone and Speaker device interfaces. StorageEngine and ServerLink also have default methods.'],
    ['Generics', 'CallRegistry<D> is written for any device type, so the tests can drive it without sockets.'],
    ['Comparable, equals and hashCode', 'AudioFrame is ordered by sequence number in the jitter buffer, with equals and hashCode consistent with compareTo.'],
    ['Polymorphism', 'The same ClientController runs over TlsLink or WebSocketLink; WalStorageEngine works with any StorageReplica.'],
    ['Inner, nested and anonymous classes, lambdas', 'ConnectionHandler is a member inner class, ServerLink.TlsLink is nested, the UI uses anonymous listeners, and lambdas are used for callbacks and thread factories.'],
    ['Exception handling', 'Custom checked exceptions become protocol error codes; try-with-resources closes sockets and files; finally blocks free compression buffers.'],
    ['Strings, StringBuffer, StringTokenizer', 'KvPayloadCodec builds payloads with StringBuffer and splits them with StringTokenizer; SearchEngine tokenises search queries.'],
    ['Collections and iterators', 'ArrayList, LinkedList (the send queue), HashMap, LinkedHashMap, ConcurrentHashMap (devices per user), and Iterator.remove() for deleting expired messages.'],
    ['Shallow and deep copy', 'Forwarding a message uses a copy constructor, so the copy never shares state with the original.'],
    ['Threads and synchronization', 'One thread per connection; wait() and notifyAll() in the send queue; synchronized storage methods; a scheduled executor for backups.'],
    ['File handling', 'Log records written through FileChannel with CRC32 checksums; GZIP snapshots; image files; call settings in a .properties file; a call statistics log that rotates at 256 KB.'],
    ['GUI and event handling', 'Swing panels, ActionListener, ItemListener, key bindings (Ctrl+D changes theme), SwingWorker, system tray notifications.'],
    ['Design patterns', 'Observer (model listeners, call statistics), Strategy (hashers, replicas, themes, quality policies), Adapter (WebGateway, RootzBlobStore), Builder (WireFrame), Factory (FrameFactory, CodecFactory), Facade (ClientController), Command (the Swing Action behind "Video on website"), State (call states and the video ladder).'],
    ['Enums and records', 'The ThemeManager.Theme enum has a constructor and overrides toString(); PushSubscription and UserRecord are records.'],
  ], [30, 70], 'Course topics and where they are used'),

  h2('3.3 A closer look at the code'),
  lead('Inheritance and our own exceptions.', 'Every error the application can raise extends one checked exception. The server catches the parent type once and turns each subclass into an error code that the client understands, so adding a new kind of error means adding one subclass and one line here.'),
  ...code(`public class EmberException extends Exception {
    public EmberException(String message) { super(message); }
}
public final class AuthorizationException extends EmberException { ... }

} catch (EmberException exception) {
    String code = exception instanceof AuthenticationLockedException ? "423"
            : exception instanceof AuthorizationException ? "403"
            : exception instanceof RateLimitedException ? "429"
            : exception instanceof AuthenticationException ? "401"
            : exception instanceof ResourceNotFoundException ? "404" : "400";
    sendError(frame.correlationId(), code, exception.getMessage());
}`),
  lead('Interfaces and polymorphism.', 'The desktop app does not care how it reaches the server. ClientController receives some ServerLink, and two static factory methods decide which class it gets. This is how one Swing app works both on the local network and against the hosted website.'),
  ...code(`public interface ServerLink extends Closeable {
    InputStream input() throws IOException;
    OutputStream output() throws IOException;
    boolean isClosed();
    static ServerLink tls(String host, int port, Path trust, char[] pw) { ... }
    static ServerLink webSocket(String address) { ... }
}
public ClientController(ServerLink link, Path clientDataRoot) { ... }`),
  lead('Threads and synchronization.', 'Messages typed in the GUI go into a queue, and a separate sender thread writes them to the network so the window never freezes. This is the producer-consumer problem from the lectures: the sender waits while the queue is empty and is woken up when something is added.'),
  ...code(`private WireFrame nextFrame() {
    synchronized (queueLock) {
        while (queue.isEmpty() && running) {
            try {
                queueLock.wait();          // enqueue() calls notifyAll()
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
        return queue.isEmpty() ? null : queue.removeFirst();
    }
}`),
  lead('Abstract class and Strategy.', 'A call quality mode is an object. QualityPolicy is abstract: it holds the rules every mode shares and leaves the numbers to three small subclasses. The rest of the call code only asks the policy, so a new mode is one new subclass.'),
  ...code(`public abstract class QualityPolicy {
    public abstract int desktopVoiceRate();
    public abstract int opusBitrate();
    public abstract VideoCeiling videoCeiling(boolean relay);
    public boolean followsDisplaySize() { return true; }
    public static final class Max extends QualityPolicy {
        @Override public int desktopVoiceRate() { return 32_000; }
        @Override public int opusBitrate() { return 40_000; }
        @Override public VideoCeiling videoCeiling(boolean relay) {
            return relay ? new VideoCeiling(720, 30) : new VideoCeiling(1080, 30);
        }
        @Override public boolean followsDisplaySize() { return false; }
    }
}`),
  lead('Deep copy.', 'When a message is forwarded, the new message gets its own id, sender, chat and a fresh copy of the text. Editing or deleting the original later has no effect on the forwarded copy.'),
  ...code(`public Message(Message source, String newClientMsgId, String newSenderUserId,
        String targetConversationId, long forwardedAtMs) {
    this(newClientMsgId, newSenderUserId, targetConversationId,
            new String(Objects.requireNonNull(source).body()), forwardedAtMs);
}`),

  h2('3.4 Multi-device sign-in and fast reloads'),
  p('For each user, the server keeps the set of all connections that are currently logged in. A new message goes to every device of every person in the chat and also to the sender\'s other devices, so a laptop shows what was just typed on a phone. Each change to a message (sending, editing, reacting, pinning, starring or deleting) gets a revision number, and a deleted message leaves a small marker behind. The website saves the chats it has already seen in the browser\'s IndexedDB database. When it reconnects, it asks only for changes after the last revision it knows, so returning users see their chats immediately and download only what is new. Logging out deletes this local copy.'),

  h2('3.5 Statuses and notifications'),
  p('A user can be online, away, on do not disturb, or invisible, and the website shows them as idle by itself after five minutes without activity. Invisible users look offline to everybody else. When a message arrives in a chat that is not open, the website shows a small card with a reply box. If the tab is in the background, it shows a system notification with Reply and Mark read buttons instead.'),
  p('When nobody has the site open, the server uses Web Push. It encrypts the notification separately for each browser using an elliptic-curve key exchange and AES-128-GCM (RFC 8291), and signs the request with a VAPID token (RFC 8292). Do not disturb turns all of these off. For safety, the server only sends to the known push services of Chrome, Firefox, Edge and Safari, each browser belongs to only one account at a time, and an account can have at most ten.'),

  h2('3.6 Saving, recovery and the off-server copy'),
  p('WalStorageEngine appends every change to a log file as a record with its length and a CRC32 checksum, and replays the log when the server starts. If the last record was only half written during a crash, the checksum does not match and that record is dropped. When the log gets large, it is compacted into a GZIP snapshot.'),
  p('With a replica attached, new records are also collected in memory. Every two seconds they are compressed, encrypted with AES-256-GCM and uploaded to the ember/data folder on rootz.so, while pictures go to ember/media. After about 1 MB of changes, a new snapshot replaces the older log files there. A wiped server downloads the newest complete snapshot and the logs after it and rebuilds its state. If the encryption key is wrong, the server stops instead of starting empty and overwriting the good copy.'),

  h2('3.7 Security'),
  bullet([['Transport: ', { bold: true }], 'TLS 1.3 for the desktop socket, and HTTPS with secure WebSockets for the website.']),
  bullet([['Passwords: ', { bold: true }], 'salted PBKDF2-HMAC-SHA256 with 600,000 iterations; five wrong attempts lock the account.']),
  bullet([['Permissions: ', { bold: true }], 'every request that names a message, group, post, story, poll or profile is checked on the server, and each rule has a test that tries to break it.']),
  bullet([['Input limits: ', { bold: true }], 'small frames before login and a size cap after it; images are checked by their file signature and size before decoding, then re-encoded.']),
  bullet([['Abuse: ', { bold: true }], 'rate limits on messages and on login attempts per address, and an audit log of security events.']),
  bullet([['End to end: ', { bold: true }], 'message text and calls are encrypted on the device; the server forwards and stores only what it cannot read (Sections 3.9 and 3.10).']),
  bullet([['Privacy: ', { bold: true }], 'only contacts see whether someone is online, calls never reveal it, the off-server copy and push messages are encrypted, and the website keeps no chats on the device after logout.']),

  h2('3.8 Keeping it light'),
  p('A free server has little memory and many students use mobile data, so we watched resource use throughout:'),
  bullet('The website uses no framework. All its files together are about 170 KB, or 56 KB compressed, and a service worker keeps them so a repeat visit loads almost nothing.'),
  bullet('WebSocket messages are compressed with a shared dictionary, so field names that repeat in every message cost almost nothing.'),
  bullet('Photos are shrunk in the browser to at most 1600 pixels before upload.'),
  bullet('Each image is cached by the browser for a year, so it downloads only once per device.'),
  bullet('Only the newest 150 messages of a chat are drawn on screen; older ones load when the user scrolls up.'),
  bullet('The server process idles at about 64 MB of memory (3 MB of live heap after garbage collection) using a small heap, the serial garbage collector and a lighter JIT setting. We measured this on a laptop with the hosted server\'s Java settings; we did not measure it on Render.'),

  h2('3.9 End-to-end encrypted chats'),
  p(`Every browser and every desktop app has its own key pair (ECDH on the P-256 curve). The private key never leaves the device; the server only stores public keys and hands out the keys of the people in a chat. To send, the device encrypts the text with a fresh random message key (AES-256-GCM), with the chat, the sender and the message id bound in so the server cannot move the text elsewhere. It then wraps that message key once for every device in the chat, including the sender\'s own other devices, using a key agreed between the two devices (ECDH, then HKDF). The server stores only the ciphertext and the wraps, and notifications say only "New message".`),
  p(`Each device remembers the devices it has seen for each person and warns when a new one appears. Profiles show device codes that two people can compare in person. The trade-offs are the same as in Signal: a new device cannot read messages sent before it existed, and logging out deletes the device\'s key. The server still sees metadata: who talks to whom and when, reactions, group names and posts.`),

  h2('3.10 Voice and video calls'),
  p(`A call can be made from any direct or group chat (up to eight people). After everyone has answered, the browsers first try to connect directly to each other with WebRTC, which gives the lowest delay. If that fails within about six seconds, if someone turned on "Hide my IP", if a desktop app is in the call, or if more than four people join, the whole call moves to a relay: the encrypted audio and video travel inside the same HTTPS WebSocket the website already uses. To a network this looks like an ordinary web page, which is why the relay still works where calling apps are blocked.`),
  p(`Either way the call is end-to-end encrypted. Every pair of people makes a fresh ECDH key pair for the call and derives a pair key; everything after that, including network addresses, is sealed with it, so the server never learns the keys or anyone\'s IP address from a call. Both screens show a 12-digit safety code made from the two public keys; matching codes mean nobody swapped the keys. On the relay, each person encrypts each packet once with a random sender key that is handed out over the pair channels, and the server copies the packet to the others without reading it, stamping it with the sender\'s account. When someone leaves, everyone makes a new sender key. The server\'s CallRegistry enforces one call per person, rings only people allowed to receive a message, and limits signalling and media per device. A call to someone who is offline or busy rings like an unanswered call, so calling never reveals whether a person is online.`),
  p(`Java has no WebRTC and no camera access, so the desktop app always uses the relay and joins with voice only. It speaks the same protocol as the website and shows the same safety codes.`),

  h2('3.11 Calls v2: measured call quality'),
  p(`For the final version we upgraded one-to-one calls behind a feature flag. The server turns it on with --calls-v2, and each device has a "Calls v2" setting; the new behaviour runs only when both are on, otherwise every older path runs unchanged. Group calls always keep the older code. All new messages between the two sides (capabilities, display size, late-frame reports, pause and key-frame requests) travel inside the existing end-to-end encrypted signals, so the server learns nothing new, and every value from the other side is checked, clamped and rate-limited.`),
  lead('Quality modes.', 'The user picks Data saver, Auto or Max (Table 6). The website sends video at the lowest of four limits: what the camera really delivers (measured from its frames), the chosen mode, the size of the other person\'s video window, and what the network carries. The statistics overlay (Ctrl+Shift+D) names the one that is limiting.'),
  ...table(['Mode', 'Direct video', 'Relayed video', 'Website voice', 'Desktop voice'], [
    ['Data saver', '360p, 15 fps', '360p, 15 fps', 'Opus 24 kbit/s', 'ADPCM 16 kHz'],
    ['Auto (default)', '720p, 30 fps', '540p, 30 fps', 'Opus 32 kbit/s', 'ADPCM 32 kHz'],
    ['Max', 'up to the camera, 1080p', '720p, 30 fps', 'Opus 40 kbit/s', 'ADPCM 32 kHz'],
  ], [20, 20, 20, 20, 20], 'Call quality modes'),
  lead('The relay ladder.', 'On the relay the video moves between five rungs, from 180p at 15 fps to 720p at 30 fps (Figure 7). Every second the website checks for trouble: frames skipped because the connection is backed up, a rising round trip, or the other side reporting late frames. On trouble it steps down at once; after six calm seconds it steps up one rung, and a rung that has just failed waits longer each time (12 s up to 60 s) so a link that can almost carry it does not flip back and forth. Direct calls pass the same limits to the browser\'s own congestion control.'),
  lead('Desktop voice.', 'The desktop voice engine (package client.call, Figure 8) opens the microphone at the best rate it offers, cleans the signal, resamples it with a 64-tap windowed-sinc filter, and encodes 20 ms frames with 32 kHz ADPCM, which the website decodes byte for byte the same. Incoming frames go through an adaptive jitter buffer that keeps 40 to 200 ms of audio, conceals a missing frame, and trims a backlog after a stall. An unplugged microphone or speaker is reopened, falling back to the system default. Speaker mode, off by default, turns the microphone down while the other person is heard, because the desktop has no echo cancellation.'),
  lead('Moving a call to the website.', 'The desktop call window has a "Video on website" button. It opens the website with the call id; the page shows only a "Join call" button, because anyone can send a link. Joining has two phases (Figure 6). First the browser asks for a single-use ticket that lasts 30 seconds and opens the camera and microphone; if the user refuses, the desktop keeps the call. Then it commits the ticket and the server swaps the device in one step: the first commit wins, the desktop is told to stop, and the other person makes new keys with the new device, so the safety code changes.'),
  wide('docs/report/figures/fig6-call-sequence.png', 'Sequence diagram: a desktop voice call, then the hand-off to the website.'),
  wide('docs/report/figures/fig7-call-states.png', 'State diagrams: the desktop call lifecycle and the relay video ladder.'),
  wide('docs/report/figures/fig8-call-crc.png', 'CRC cards for the call classes.'),
  p('Table 7 lists what we measured against the targets we set before building. All of it was measured on one laptop with a local server, in Chrome only, with a synthetic camera and microphone; slow networks were made with a small proxy we wrote that limits bandwidth and adds delay to the relay connection. We did not test Firefox or Safari, real devices being unplugged, direct calls on a lossy network, or the hosted server\'s memory and CPU; those are marked "not measured".'),
  ...table(['What', 'Target', 'Measured'], [
    ['Desktop and website voice', '32 kHz when both can', 'ADPCM 32 kHz both ways, about 130 kbit/s; desktop jitter buffer 40-60 ms'],
    ['Recovery from a 500 ms stall', 'normal delay within 3 s', 'back to 80 ms or less within 3 s (before: a lasting extra 140 ms)'],
    ['Changing the mode during a call', 'within 2 s', 'within 1.5 s'],
    ['Step down when the link drops to 400 kbit/s', 'within 2 s of trouble', 'trouble seen 1.8 s after the drop, first step within the next second; 180p reached after 4.8 s; voice kept going'],
    ['Link at 1.2 Mbit/s', 'keep 540p', '540p at 30 fps held'],
    ['Direct video bitrate', 'within 10 % of the cap', '3 % under the cap'],
    ['640x480 camera', 'never sent larger', 'never above 480 lines'],
    ['Hand-off', 'old device stops, keys change', 'yes; joined in 247 ms on a local server; a refused camera left the call untouched'],
    ['Mouth-to-ear delay, Render CPU and memory', '', 'not measured'],
  ], [30, 24, 46], 'Calls v2: measured against targets'),

  h2('3.12 Testing'),
  p('We wrote our own small test runner (EmberTestSuite) so the project needs no libraries. The tests start real servers on free ports and talk to them over real TLS sockets and WebSockets, just like the apps do. All 46 pass. Table 8 groups them by area. The website\'s call-quality rules are plain JavaScript with no page attached, so 9 more tests run them with Node\'s built-in test runner (also no libraries); they share audio test vectors with the Java tests so both sides must encode voice byte for byte the same. The build script runs both.'),
  ...table(['Area', 'What the tests check', 'Tests'], tests, [22, 66, 12], 'Automated tests (all passing)', { center: [2] }),
  p('Three extra tools support the demo. AttackSimulator runs nine attacks against a live server, such as forged frames, password guessing and a script renamed as an image, and shows each one being refused. ConcurrencyStressHarness sends 500 messages from ten clients at once and checks that nothing is lost, duplicated or out of order. ReplicaCheck saves and restores throwaway files on rootz.so to test the backup connection.'),

  h2('3.13 Screenshots'),
  p('Figures 9 to 15 show the website and Figures 16 and 17 the desktop app, in several of the available themes.'),
  ...figure('docs/screenshots/web-login-green.jpg', 'Login screen of the website (Phosphor green theme).'),
  ...figure('docs/screenshots/web-chat-theme-picker.jpg', 'Direct chat with a pinned message, a starred message, a long message with "Read more", and the theme picker open (Amber theme).'),
  ...figure('docs/screenshots/web-group-poll-c64.jpg', 'Group chat with a poll (C64 theme).'),
  ...figure('docs/screenshots/web-notification-pocket.jpg', 'Notification card with a quick-reply box for a message from another chat (Pocket theme).'),
  ...figure('docs/screenshots/web-pulse-synthwave.jpg', 'Campus Pulse feed with stories along the top (Synthwave theme).'),
  ...figure('docs/screenshots/web-profile-paper.jpg', 'A private profile with follow requests waiting for approval (Paper theme).'),
  ...figure('docs/screenshots/web-mobile-chat.jpg', 'The website on a phone-sized screen.', 280),
  ...figure('docs/screenshots/desktop-chat-amber.png', 'The Swing desktop app: a direct chat with read ticks, reactions, pin and star (Amber theme).'),
  ...figure('docs/screenshots/desktop-chat-synth.png', 'The same chat in the Synthwave theme.'),

  h2('3.14 Problems we ran into'),
  p('Not everything worked the first time. A few of the problems taught us more than the features did:'),
  bullet([['The disappearing server. ', { bold: true }], 'Our first online demo ran through a free tunnel from a laptop. The link changed on every restart and died whenever the laptop slept. Moving to Render fixed the link, but Render wipes its disk on restart, which is why we built the encrypted off-server copy.']),
  bullet([['Files that would not go away. ', { bold: true }], 'On rootz.so, a deleted file still appeared in the folder listing but returned "not found" when downloaded. Our restore code had to treat a listed file as possibly missing. The first upload of a session could also take more than 30 seconds, so the server now waits up to 110 seconds on shutdown to finish uploading.']),
  bullet([['Swing ignoring our colours. ', { bold: true }], 'With the Nimbus look and feel, the usual UIManager colour keys did nothing, and the theme drop-down showed dark text on a dark background. We had to set Nimbus\'s own palette keys and write a small custom renderer for the combo box.']),
  bullet([['A test that failed one time in ten. ', { bold: true }], 'The poll test sometimes checked a second client before the poll had reached it. Adding a sleep would only hide the race, so the test now waits until the condition is true, with a time limit.']),
  bullet([['Security review. ', { bold: true }], 'A review of our notification code found that a shared browser could keep receiving the previous user\'s notifications, and that a visitor could influence an address the server sends to push services. Both were fixed and now have tests.']),
  bullet([['Calls that would not connect. ', { bold: true }], 'Calling apps are restricted on many networks in the UAE, so direct WebRTC calls often failed. We added a relay that carries the encrypted call inside the website\'s normal HTTPS WebSocket, which such networks treat like any web page.']),
  bullet([['Video that stopped for good. ', { bold: true }], 'While testing on a slowed-down link, one side\'s "window visible again" signal never arrived, so the other side kept its video paused for the rest of the call. Each side now repeats its pause state every two seconds when it has changed.']),
  bullet([['A ticket that the wrong tab could burn. ', { bold: true }], 'The first hand-off test showed that a second browser tab presenting the first tab\'s ticket used it up, so the right tab then failed too. Tickets now ignore every device except the one they were issued to.']),

  h1('4. Conclusion and Future Work'),
  h2('4.1 What we achieved'),
  p('We set out to build a messenger we would actually use, and Ember does what we wanted. The same account can be open on a phone, a laptop and the desktop app at once, with live messages, ticks, statuses and notifications on all of them. It encrypts message text and calls end to end, and its calls adapt to the connection and keep working on networks that block calling apps. It runs online for free, keeps an encrypted copy of its data off the server, and recovers from crashes and restarts without losing messages, with no libraries beyond the JDK.'),
  p('The bigger result for us was the learning. Interfaces stopped being an exam topic once we had to swap a TLS socket for a WebSocket without touching the rest of the client, or test the backup code against a fake store. Threads made sense once a frozen window showed us why the network code needed its own thread. Writing a log file that survives a power cut taught us more about file handling than any exercise had. The automated tests, the attack simulator and the stress harness gave us confidence that the system works and is not just a demo that happens to run.'),
  h2('4.2 Future work'),
  bullet('Opus voice in the desktop app (a pure-Java decoder exists), so desktop calls use less data at the same quality.'),
  bullet('Our own TURN server, so more calls can go directly between devices, and testing direct calls on lossy networks.'),
  bullet('Hiding more metadata from the server: today it still sees who talks to whom and when.'),
  bullet('Voice notes and file attachments, uploaded in chunks.'),
  bullet('Searching across all chats on the server, not only inside one chat.'),
  bullet('Virtual threads (Java 21) so the same memory can serve many more connections.'),
  bullet('An SQL database through JDBC as another StorageEngine implementation.'),
  bullet('Right-to-left layout for Arabic, and a small Android wrapper around the website.'),

  h1('5. References'),
  ...[
    'C. Horstmann, Object-Oriented Design and Patterns, 2nd ed. John Wiley and Sons, 2006. (Course textbook.)',
    'H. Schildt, Java 2: The Complete Reference, 5th ed. Tata McGraw-Hill. (Course reference book.)',
    'Oracle, Java SE 21 API Specification (java.net.http, javax.crypto, javax.net.ssl, javax.swing, java.nio). https://docs.oracle.com/en/java/javase/21/docs/api/',
    'E. Rescorla, The Transport Layer Security (TLS) Protocol Version 1.3, IETF RFC 8446, 2018.',
    'I. Fette and A. Melnikov, The WebSocket Protocol, IETF RFC 6455, 2011; T. Yoshino, Compression Extensions for WebSocket, IETF RFC 7692, 2015.',
    'M. Thomson, Message Encryption for Web Push, IETF RFC 8291, 2017; M. Thomson and P. Beverloo, Voluntary Application Server Identification (VAPID) for Web Push, IETF RFC 8292, 2017.',
    'OWASP Foundation, Password Storage Cheat Sheet. https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html',
    'Mozilla, MDN Web Docs: Service Worker API, Push API, Notifications API and IndexedDB API. https://developer.mozilla.org/',
    'Render, Blueprint specification and Docker web services documentation. https://render.com/docs',
    'rootz.so, API documentation. https://rootz.so/docs',
    'W3C, WebRTC: Real-Time Communication in Browsers, and WebCodecs. https://www.w3.org/TR/webrtc/ and https://www.w3.org/TR/webcodecs/',
    'H. Schulzrinne et al., RTP: A Transport Protocol for Real-Time Applications, IETF RFC 3550, 2003 (jitter estimate); J.-M. Valin et al., Definition of the Opus Audio Codec, IETF RFC 6716, 2012.',
    'H. Krawczyk and P. Eronen, HMAC-based Extract-and-Expand Key Derivation Function (HKDF), IETF RFC 5869, 2010.',
  ].map((r, i) => new Paragraph({ spacing: { after: 120 }, indent: { left: 567, hanging: 567 },
    children: [run('[' + (i + 1) + ']\t', { bold: true }), run(r)], tabStops: [{ type: TabStopType.LEFT, position: 567 }] })),
];

const small = (text, o = {}) => new TextRun({ text, font: FONT, size: 20, color: '555555', ...o });
const header = (width) => ({ default: new Header({ children: [new Paragraph({ tabStops: [{ type: TabStopType.RIGHT, position: width }],
  border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: RULE, space: 4 } },
  children: [small('Ember', { bold: true, color: ACCENT }), small('	CS F213 Project Report')] })] }) });
const footer = { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER,
  children: [new TextRun({ font: FONT, size: 20, color: '555555', children: [PageNumber.CURRENT] })] })] }) };
const portrait = { size: { width: PAGE_W, height: PAGE_H }, margin: { top: M_TOP, bottom: M_BOTTOM, left: M_LEFT, right: M_RIGHT, header: 640, footer: 640 } };
const landscape = { size: { width: PAGE_W, height: PAGE_H, orientation: PageOrientation.LANDSCAPE },
  margin: { top: L_MARGIN, bottom: L_MARGIN, left: L_MARGIN, right: L_MARGIN, header: 500, footer: 500 } };

// Split the body into portrait sections with a landscape section for each wide figure.
const sections = [{ properties: { page: portrait }, children: cover }];
let chunk = [];
let first = true;
const flush = (page) => {
  if (!chunk.length) return; // back-to-back wide figures need no portrait page between them
  sections.push({ properties: { page, ...(first ? { pageNumbers: { start: 1 } } : {}) },
    headers: header(page === portrait ? TEXT_W : L_TEXT_W), footers: footer, children: chunk });
  chunk = []; first = false;
};
for (const item of body) {
  if (item && item.wide) { flush(portrait); chunk = item.wide; flush(landscape); } else chunk.push(item);
}
flush(portrait);

const doc = new Document({
  creator: 'Ember project team', title: 'Ember: Project Report', description: 'CS F213 project report',
  features: { updateFields: true },
  styles: {
    default: { document: { run: { font: FONT, size: 24 }, paragraph: { spacing: { line: 276 } } } },
    paragraphStyles: [
      { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { font: FONT, size: 28, bold: true, color: ACCENT, allCaps: true, characterSpacing: 20 },
        paragraph: { spacing: { before: 0, after: 240 }, keepNext: true, outlineLevel: 0 } },
      { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { font: FONT, size: 28, bold: true, color: '222222' },
        paragraph: { spacing: { before: 300, after: 120 }, keepNext: true, outlineLevel: 1 } },
    ],
  },
  numbering: { config: [{ reference: 'bullets', levels: [{ level: 0, format: LevelFormat.BULLET, text: '▪', alignment: AlignmentType.LEFT,
    style: { paragraph: { indent: { left: 500, hanging: 280 } }, run: { color: ACCENT } } }] }] },
  sections,
});
Packer.toBuffer(doc).then((buf) => { fs.writeFileSync(OUT, buf); console.log('wrote ' + OUT); });
