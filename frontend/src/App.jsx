import { useEffect, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import { api, setToken, getToken, upload } from './api.js'

const hue = s => [...s].reduce((a, c) => a * 31 + c.charCodeAt(0), 7) % 360
const Avatar = ({ name }) => <span className="av" style={{ background: `hsl(${hue(name)} 45% 40%)` }}>{name[0].toUpperCase()}</span>
const clock = iso => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
const isImg = n => /\.(png|jpe?g|gif|webp)$/i.test(n || '')

function Auth({ onAuth }) {
  const [mode, setMode] = useState('login'), [f, setF] = useState({ username: '', password: '' }), [err, setErr] = useState('')
  const submit = async e => { e.preventDefault(); setErr('')
    try { const r = await api('/auth/' + mode, 'POST', f); setToken(r.token); localStorage.setItem('user', r.username); onAuth(r.username) } catch (x) { setErr(x.message) } }
  return (<div className="auth"><form onSubmit={submit}>
    <h1>Pulse</h1><p className="muted">{mode === 'login' ? 'Sign in to rejoin your rooms.' : 'Pick a username and jump into the conversation.'}</p>
    <label>Username<input value={f.username} onChange={e => setF({ ...f, username: e.target.value })} autoFocus /></label>
    <label>Password<input type="password" value={f.password} onChange={e => setF({ ...f, password: e.target.value })} /></label>
    {err && <div className="error">{err}</div>}
    <button className="primary">{mode === 'login' ? 'Sign in' : 'Create account'}</button>
    <button type="button" className="link" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setErr('') }}>{mode === 'login' ? 'Need an account? Register' : 'Have an account? Sign in'}</button>
  </form></div>)
}

export default function App() {
  const [user, setUser] = useState(localStorage.getItem('token') ? localStorage.getItem('user') : null)
  const [rooms, setRooms] = useState([]), [active, setActive] = useState(null), [msgs, setMsgs] = useState({}), [unread, setUnread] = useState({})
  const [online, setOnline] = useState([]), [users, setUsers] = useState([]), [conn, setConn] = useState(false), [typing, setTyping] = useState('')
  const [text, setText] = useState(''), [newRoom, setNewRoom] = useState(''), [error, setError] = useState(''), [edit, setEdit] = useState(null), [busy, setBusy] = useState(false)
  const client = useRef(null), subs = useRef({}), activeRef = useRef(null), tTimer = useRef(null), lastT = useRef(0), end = useRef(null), fileIn = useRef(null)

  const addRoom = r => setRooms(x => x.some(y => y.id === r.id) ? x : [...x, r])
  const onMsg = n => {
    setMsgs(p => { const l = p[n.roomId] || [], i = l.findIndex(x => x.id === n.id)
      return { ...p, [n.roomId]: i >= 0 ? l.map((x, j) => j === i ? n : x) : [...l, n] } })
    if (!n.edited && !n.deleted && n.roomId !== activeRef.current && n.sender !== user) setUnread(u => ({ ...u, [n.roomId]: (u[n.roomId] || 0) + 1 }))
  }
  const title = r => r.dmA ? (r.dmA === user ? r.dmB : r.dmA) : r.name

  useEffect(() => { if (!user) return
    api('/rooms').then(r => { setRooms(r); setActive(a => a ?? r[0]?.id ?? null) }).catch(e => setError(e.message))
    const refreshUsers = () => api('/users').then(setUsers).catch(() => {}); refreshUsers()
    const c = new Client({ brokerURL: (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws',
      connectHeaders: { Authorization: 'Bearer ' + getToken() }, reconnectDelay: 3000,
      onConnect: () => { subs.current = {}
        c.subscribe('/topic/presence', m => { setOnline(JSON.parse(m.body)); refreshUsers() })
        c.subscribe('/topic/rooms', m => addRoom(JSON.parse(m.body)))
        c.subscribe('/topic/dm.' + user, m => addRoom(JSON.parse(m.body)))
        api('/online').then(setOnline); setConn(true) },
      onWebSocketClose: () => setConn(false), onStompError: () => setConn(false) })
    c.activate(); client.current = c; return () => c.deactivate() }, [user])

  // one live subscription per room so unread counts work for rooms you are not viewing
  useEffect(() => { if (!conn) return
    rooms.forEach(r => { if (!subs.current[r.id]) subs.current[r.id] = client.current.subscribe('/topic/room.' + r.id, m => onMsg(JSON.parse(m.body))) }) }, [conn, rooms])

  useEffect(() => { activeRef.current = active; if (!active) return
    setUnread(u => ({ ...u, [active]: 0 })); setTyping(''); setEdit(null)
    api(`/rooms/${active}/messages`).then(h => setMsgs(p => ({ ...p, [active]: [...h, ...(p[active] || []).filter(n => !h.some(y => y.id === n.id))] }))).catch(e => setError(e.message))
    if (!conn) return
    const s = client.current.subscribe('/topic/typing.' + active, m => { const u = JSON.parse(m.body).user; if (u === user) return
      setTyping(u); clearTimeout(tTimer.current); tTimer.current = setTimeout(() => setTyping(''), 2000) })
    return () => s.unsubscribe() }, [conn, active])

  const list = msgs[active] || []
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth' }) }, [list.length, active])
  if (!user) return <Auth onAuth={setUser} />

  const room = rooms.find(r => r.id === active)
  const guard = fn => async (...a) => { try { setError(''); await fn(...a) } catch (e) { setError(e.message) } }
  const publish = body => client.current.publish({ destination: '/app/chat/' + active, body: JSON.stringify(body) })
  const send = e => { e.preventDefault(); if (!text.trim() || !conn) return; publish({ content: text }); setText('') }
  const onType = e => { setText(e.target.value)
    if (conn && Date.now() - lastT.current > 1000) { lastT.current = Date.now(); client.current.publish({ destination: '/app/typing/' + active }) } }
  const createRoom = guard(async e => { e.preventDefault(); const r = await api('/rooms', 'POST', { name: newRoom }); addRoom(r); setActive(r.id); setNewRoom('') })
  const openDm = guard(async name => { const r = await api('/dm', 'POST', { username: name }); addRoom(r); setActive(r.id) })
  const saveEdit = guard(async e => { e.preventDefault(); await api('/messages/' + edit.id, 'PUT', { content: edit.text }); setEdit(null) })
  const delMsg = guard(async id => { if (confirm('Delete this message?')) await api('/messages/' + id, 'DELETE') })
  const attach = guard(async e => { const f = e.target.files[0]; e.target.value = ''; if (!f) return
    if (f.size > 5 * 1024 * 1024) throw new Error('File is larger than 5 MB')
    setBusy(true); try { const r = await upload(f); publish({ content: text, fileUrl: r.url, fileName: r.name }); setText('') } finally { setBusy(false) } })
  const dmRoom = u => rooms.find(r => r.dmA && (r.dmA === u || r.dmB === u))
  const Badge = ({ id }) => unread[id] > 0 ? <span className="badge">{unread[id]}</span> : null

  return (<div className="shell">
    <aside>
      <h2>Pulse</h2>
      <h3>Rooms</h3>
      <nav>{rooms.filter(r => !r.dmA).map(r => <button key={r.id} className={'room' + (active === r.id ? ' on' : '')} onClick={() => setActive(r.id)}># {r.name}<Badge id={r.id} /></button>)}</nav>
      <form onSubmit={createRoom}><input placeholder="New room name" value={newRoom} onChange={e => setNewRoom(e.target.value)} /><button className="primary">Create room</button></form>
      <h3>Direct messages</h3>
      <nav>{users.map(u => { const d = dmRoom(u)
        return <button key={u} className={'room dm' + (d && active === d.id ? ' on' : '')} onClick={() => openDm(u)}>
          <span className={online.includes(u) ? 'dot ok' : 'dot off'} /><Avatar name={u} />{u}{d && <Badge id={d.id} />}</button> })}
        {!users.length && <small className="hint">Other users appear here once they register.</small>}</nav>
      <div className="grow" />
      <div className="me"><span className={conn ? 'dot ok' : 'dot'} />{conn ? user : 'Reconnecting…'} · {online.length} online
        <button className="link" onClick={() => { client.current?.deactivate(); setToken(null); localStorage.removeItem('user'); setUser(null) }}>Sign out</button></div>
    </aside>
    <main>
      <header><h1>{room ? (room.dmA ? '@ ' : '# ') + title(room) : 'Pick a room'}</h1></header>
      {error && <div className="error">{error}</div>}
      <div className="feed">
        {!list.length && room && <p className="muted center">No messages here yet. Say hello.</p>}
        {list.map((m, i) => { const mine = m.sender === user, same = list[i - 1]?.sender === m.sender
          return (<div key={m.id} className={'msg' + (mine ? ' mine' : '')}>
            {!same && <div className="who"><Avatar name={m.sender} /><b>{mine ? 'You' : m.sender}</b><small>{clock(m.sentAt)}</small></div>}
            <div className="bubble">
              {m.deleted ? <em className="gone">This message was deleted</em> : edit?.id === m.id ? (
                <form onSubmit={saveEdit} className="editf"><input autoFocus value={edit.text} onChange={e => setEdit({ ...edit, text: e.target.value })} />
                  <button className="mini">Save</button><button type="button" className="mini" onClick={() => setEdit(null)}>Cancel</button></form>
              ) : <>
                {m.content && <p>{m.content}</p>}
                {m.fileUrl && (isImg(m.fileName) ? <a href={m.fileUrl} target="_blank" rel="noreferrer"><img className="shot" src={m.fileUrl} alt={m.fileName} /></a>
                  : <a className="file" href={m.fileUrl} download={m.fileName}>📎 {m.fileName}</a>)}
                {m.edited && <small className="ed">edited</small>}
                {mine && <span className="acts">{m.content && <button aria-label="Edit" onClick={() => setEdit({ id: m.id, text: m.content })}>✎</button>}<button aria-label="Delete" onClick={() => delMsg(m.id)}>×</button></span>}
              </>}
            </div></div>) })}
        <div ref={end} />
      </div>
      <div className="typing">{typing && `${typing} is typing…`}</div>
      <form className="composer" onSubmit={send}>
        <input type="file" ref={fileIn} onChange={attach} hidden />
        <button type="button" className="clip" aria-label="Attach a file" disabled={!conn || !room || busy} onClick={() => fileIn.current.click()}>{busy ? '…' : '📎'}</button>
        <input value={text} onChange={onType} maxLength={1000} placeholder={conn ? `Message ${room ? title(room) : ''}` : 'Connecting…'} disabled={!conn || !room} />
        <button className="primary" disabled={!conn || !text.trim()}>Send</button></form>
    </main></div>)
}
