# Pulse
Real-time team chat. React 18 + Vite + @stomp/stompjs frontend; Spring Boot 3 (Java 17) with WebSocket/STOMP, JWT auth, JPA + file-based H2.
Features: public rooms with live room creation, 1-to-1 direct messages, edit/delete own messages, image and file sharing (5 MB), unread badges, live presence, typing indicators, auto-reconnect.

## Requirements (Windows): JDK 17+, Maven 3.9+, Node 18+ on PATH
## Run
    cd pulse
    powershell -ExecutionPolicy Bypass -File .\run.ps1
Open http://localhost:5173 in two browser windows (different accounts) to see it live. API :8080, H2 console /h2-console (`jdbc:h2:file:./data/pulse`, user `sa`).

## Protocol
REST: POST /api/auth/register|login, GET/POST /api/rooms, GET /api/rooms/{id}/messages, GET /api/online
STOMP at ws://localhost:8080/ws (CONNECT header `Authorization: Bearer <jwt>`):
send /app/chat/{roomId} {content}, /app/typing/{roomId}; subscribe /topic/room.{id}, /topic/typing.{id}, /topic/presence
Change `app.jwt.secret` before deploying.

Extra endpoints: GET /api/users, POST /api/dm {username}, PUT/DELETE /api/messages/{id}, POST /api/upload (multipart), GET /api/files/{name}. Uploads are stored in backend/uploads.
