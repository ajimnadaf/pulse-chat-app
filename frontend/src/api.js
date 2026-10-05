let token = localStorage.getItem('token')
export const setToken = t => { token = t; t ? localStorage.setItem('token', t) : localStorage.removeItem('token') }
export async function api(path, method = 'GET', body) {
  const res = await fetch('/api' + path, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined })
  if (res.status === 401 && token) { setToken(null); location.reload() }
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.error || 'Request failed (' + res.status + ')') }
  return res.status === 204 ? null : res.json()
}
export async function download(path, name) {
  const res = await fetch('/api' + path, { headers: { Authorization: 'Bearer ' + token } })
  if (!res.ok) throw new Error('Export failed')
  const a = document.createElement('a'); a.href = URL.createObjectURL(await res.blob()); a.download = name; a.click()
}
export const getToken = () => token
export async function upload(file) {
  const fd = new FormData(); fd.append('file', file)
  const res = await fetch('/api/upload', { method: 'POST', headers: { Authorization: 'Bearer ' + token }, body: fd })
  if (!res.ok) throw new Error('Upload failed (max 5 MB)')
  return res.json()
}
