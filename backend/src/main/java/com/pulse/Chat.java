package com.pulse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.messaging.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

record RoomReq(@NotBlank @Size(min=2,max=30) @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]+", message="use letters, numbers, - or _") String name) {}
record ChatIn(String content, String fileUrl, String fileName) {}
record DmReq(@NotBlank String username) {}

@Configuration @EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
  private static final java.util.regex.Pattern ROOM = java.util.regex.Pattern.compile("/topic/(?:room|typing)\\.(\\d+)");
  private final JwtService jwt; private final RoomRepo rooms;
  WebSocketConfig(JwtService j, RoomRepo r){ jwt = j; rooms = r; }
  @Override public void registerStompEndpoints(StompEndpointRegistry r){ r.addEndpoint("/ws").setAllowedOriginPatterns("*"); }
  @Override public void configureMessageBroker(MessageBrokerRegistry r){ r.enableSimpleBroker("/topic"); r.setApplicationDestinationPrefixes("/app"); }
  @Override public void configureClientInboundChannel(ChannelRegistration reg){
    reg.interceptors(new ChannelInterceptor() {
      @Override public org.springframework.messaging.Message<?> preSend(org.springframework.messaging.Message<?> msg, MessageChannel ch){
        StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(msg, StompHeaderAccessor.class);
        if (a == null || a.getCommand() == null) return msg;
        if (StompCommand.CONNECT.equals(a.getCommand())) {
          String h = a.getFirstNativeHeader("Authorization");
          String user = h != null && h.startsWith("Bearer ") ? jwt.parse(h.substring(7)) : null;
          if (user == null) throw new MessagingException("Unauthorized");
          a.setUser(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        } else if (!StompCommand.DISCONNECT.equals(a.getCommand())) {
          if (a.getUser() == null) throw new MessagingException("Unauthorized");
          if (StompCommand.SUBSCRIBE.equals(a.getCommand()) && a.getDestination() != null) {
            String d = a.getDestination(), u = a.getUser().getName();
            if (d.startsWith("/topic/dm.") && !d.equals("/topic/dm." + u)) throw new MessagingException("Forbidden");
            var m = ROOM.matcher(d);
            if (m.matches()) { Room r = rooms.findById(Long.valueOf(m.group(1))).orElse(null);
              if (r == null || !ChatSocket.allowed(r, u)) throw new MessagingException("Forbidden"); }
          }
        }
        return msg;
      }
    });
  }
}

@Controller
class ChatSocket {
  private final SimpMessagingTemplate out; private final MessageRepo messages; private final RoomRepo rooms;
  private final Map<String,String> sessions = new ConcurrentHashMap<>();
  ChatSocket(SimpMessagingTemplate o, MessageRepo m, RoomRepo r){ out = o; messages = m; rooms = r; }
  static boolean allowed(Room r, String u){ return r.dmA == null || u.equals(r.dmA) || u.equals(r.dmB); }

  @MessageMapping("/chat/{roomId}")
  void chat(@DestinationVariable Long roomId, ChatIn in, Principal p){
    Room r = rooms.findById(roomId).orElse(null); if (r == null || !allowed(r, p.getName())) return;
    String text = in.content() == null ? "" : in.content().trim();
    boolean file = in.fileUrl() != null && in.fileUrl().matches("/api/files/[A-Za-z0-9._-]+");
    if ((text.isEmpty() && !file) || text.length() > 1000) return;
    Message m = new Message(); m.roomId = roomId; m.sender = p.getName(); m.content = text; m.sentAt = Instant.now();
    m.edited = false; m.deleted = false;
    if (file) { m.fileUrl = in.fileUrl(); String n = in.fileName() == null ? "file" : in.fileName(); m.fileName = n.length() > 80 ? n.substring(0, 80) : n; }
    out.convertAndSend("/topic/room." + roomId, ChatOut.of(messages.save(m)));
  }
  @MessageMapping("/typing/{roomId}")
  void typing(@DestinationVariable Long roomId, Principal p){
    Room r = rooms.findById(roomId).orElse(null);
    if (r != null && allowed(r, p.getName())) out.convertAndSend("/topic/typing." + roomId, Map.of("user", p.getName())); }

  List<String> online(){ return sessions.values().stream().distinct().sorted().toList(); }
  @EventListener void connected(SessionConnectedEvent e){
    Principal u = e.getUser(); if (u == null) return;
    sessions.put(StompHeaderAccessor.wrap(e.getMessage()).getSessionId(), u.getName()); out.convertAndSend("/topic/presence", online()); }
  @EventListener void disconnected(SessionDisconnectEvent e){ sessions.remove(e.getSessionId()); out.convertAndSend("/topic/presence", online()); }
}

@RestController @RequestMapping("/api")
class ChatApi {
  private final RoomRepo rooms; private final MessageRepo messages; private final UserRepo users; private final ChatSocket socket; private final SimpMessagingTemplate out;
  ChatApi(RoomRepo r, MessageRepo m, UserRepo u, ChatSocket s, SimpMessagingTemplate o){ rooms = r; messages = m; users = u; socket = s; out = o; }
  private static ResponseStatusException err(HttpStatus s, String m){ return new ResponseStatusException(s, m); }

  @GetMapping("/rooms") List<Room> rooms(Principal me){ return rooms.findAll().stream().filter(r -> ChatSocket.allowed(r, me.getName())).toList(); }
  @PostMapping("/rooms") Room create(@RequestBody @Valid RoomReq r, Principal me){
    if (rooms.existsByNameIgnoreCase(r.name())) throw err(HttpStatus.CONFLICT, "A room with that name exists");
    Room room = new Room(); room.name = r.name(); room.createdBy = me.getName(); room = rooms.save(room);
    out.convertAndSend("/topic/rooms", room); return room;
  }
  @GetMapping("/users") List<String> users(Principal me){ return users.findAll().stream().map(u -> u.username).filter(n -> !n.equals(me.getName())).sorted().toList(); }
  @PostMapping("/dm") Room dm(@RequestBody @Valid DmReq r, Principal me){
    String a = me.getName(), b = r.username();
    if (a.equals(b) || users.findByUsername(b).isEmpty()) throw err(HttpStatus.BAD_REQUEST, "No such user");
    String[] s = {a, b}; Arrays.sort(s); String name = "dm:" + s[0] + ":" + s[1];
    return rooms.findByName(name).orElseGet(() -> { Room x = new Room(); x.name = name; x.createdBy = a; x.dmA = s[0]; x.dmB = s[1];
      Room saved = rooms.save(x); out.convertAndSend("/topic/dm." + s[0], saved); out.convertAndSend("/topic/dm." + s[1], saved); return saved; });
  }
  @GetMapping("/rooms/{id}/messages") List<ChatOut> history(@PathVariable Long id, Principal me){
    Room r = rooms.findById(id).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "Room not found"));
    if (!ChatSocket.allowed(r, me.getName())) throw err(HttpStatus.FORBIDDEN, "Not your conversation");
    var l = new ArrayList<>(messages.findTop50ByRoomIdOrderByIdDesc(id).stream().map(ChatOut::of).toList()); Collections.reverse(l); return l; }
  @GetMapping("/online") List<String> online(){ return socket.online(); }

  private Message mine(Long id, Principal me){
    Message m = messages.findById(id).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "Message not found"));
    if (!m.sender.equals(me.getName())) throw err(HttpStatus.FORBIDDEN, "You can only change your own messages"); return m; }
  private ChatOut push(Message m){ ChatOut o = ChatOut.of(messages.save(m)); out.convertAndSend("/topic/room." + m.roomId, o); return o; }
  @PutMapping("/messages/{id}") ChatOut edit(@PathVariable Long id, @RequestBody ChatIn in, Principal me){
    Message m = mine(id, me); String t = in.content() == null ? "" : in.content().trim();
    if (t.isEmpty() || t.length() > 1000 || Boolean.TRUE.equals(m.deleted)) throw err(HttpStatus.BAD_REQUEST, "Invalid message");
    m.content = t; m.edited = true; return push(m); }
  @DeleteMapping("/messages/{id}") ChatOut delete(@PathVariable Long id, Principal me){
    Message m = mine(id, me); m.deleted = true; m.content = ""; m.fileUrl = null; m.fileName = null; return push(m); }

  @PostMapping("/upload") Map<String,String> upload(@RequestParam MultipartFile file) throws IOException {
    if (file.isEmpty()) throw err(HttpStatus.BAD_REQUEST, "Empty file");
    String orig = Optional.ofNullable(file.getOriginalFilename()).orElse("file");
    String ext = orig.contains(".") ? orig.substring(orig.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9]", "") : "";
    if (ext.length() > 8) ext = ext.substring(0, 8);
    String id = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
    Path dir = Path.of("uploads").toAbsolutePath(); Files.createDirectories(dir); file.transferTo(dir.resolve(id));
    return Map.of("url", "/api/files/" + id, "name", orig.length() > 80 ? orig.substring(0, 80) : orig);
  }
  @GetMapping("/files/{name:.+}") ResponseEntity<Resource> file(@PathVariable String name){
    if (!name.matches("[A-Za-z0-9._-]+") || name.contains("..")) throw err(HttpStatus.BAD_REQUEST, "Bad file name");
    Path p = Path.of("uploads").toAbsolutePath().resolve(name); if (!Files.exists(p)) throw err(HttpStatus.NOT_FOUND, "File not found");
    boolean img = name.toLowerCase().matches(".*\\.(png|jpe?g|gif|webp)$");
    var b = ResponseEntity.ok().contentType(img ? MediaTypeFactory.getMediaType(name).orElse(MediaType.APPLICATION_OCTET_STREAM) : MediaType.APPLICATION_OCTET_STREAM);
    if (!img) b.header(HttpHeaders.CONTENT_DISPOSITION, "attachment");
    return b.body(new FileSystemResource(p));
  }
}

@Configuration
class Seed { @Bean CommandLineRunner seedRooms(RoomRepo r){ return a -> { if (r.count() == 0) for (String n : List.of("general","random")) { Room x = new Room(); x.name = n; x.createdBy = "system"; r.save(x); } }; } }
