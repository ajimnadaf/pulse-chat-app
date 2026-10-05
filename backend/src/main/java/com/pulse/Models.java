package com.pulse;
import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Entity @Table(name="users")
class AppUser { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
  @Column(unique=true,nullable=false) public String username; @com.fasterxml.jackson.annotation.JsonIgnore public String passwordHash; }

@Entity
class Room { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
  @Column(unique=true,nullable=false) public String name; public String createdBy; public String dmA, dmB; }

@Entity @Table(indexes=@Index(columnList="roomId"))
class Message { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
  public Long roomId; public String sender; @Column(length=1000) public String content; public Instant sentAt;
  public Boolean edited, deleted; public String fileUrl, fileName; }

record ChatOut(Long id, Long roomId, String sender, String content, String sentAt, boolean edited, boolean deleted, String fileUrl, String fileName) {
  static ChatOut of(Message m){ return new ChatOut(m.id, m.roomId, m.sender, m.content, m.sentAt.toString(),
    Boolean.TRUE.equals(m.edited), Boolean.TRUE.equals(m.deleted), m.fileUrl, m.fileName); } }

interface UserRepo extends JpaRepository<AppUser,Long> { Optional<AppUser> findByUsername(String u); }
interface RoomRepo extends JpaRepository<Room,Long> { boolean existsByNameIgnoreCase(String n); Optional<Room> findByName(String n); }
interface MessageRepo extends JpaRepository<Message,Long> { List<Message> findTop50ByRoomIdOrderByIdDesc(Long roomId); }
