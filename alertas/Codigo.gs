// Alertas del panel de pantallas de La Cole.
// Recibe una petición del servidor y envía un correo desde esta cuenta de Google.
// Antes de publicar, pon una CLAVE larga y secreta aquí (la misma que se escribe en el panel).
const CLAVE = 'CAMBIA-ESTA-CLAVE';
const DESTINOS_PERMITIDOS = ['lacole.edu.co']; // solo se envía a estos dominios

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.secret !== CLAVE || CLAVE === 'CAMBIA-ESTA-CLAVE') return salida({ ok: false, error: 'clave incorrecta' });
    const para = String(d.to || '').split(',').map(s => s.trim()).filter(Boolean);
    if (!para.length) return salida({ ok: false, error: 'sin destinatarios' });
    for (const p of para) {
      if (!DESTINOS_PERMITIDOS.includes(p.split('@')[1])) return salida({ ok: false, error: 'destino no permitido: ' + p });
    }
    GmailApp.sendEmail(para.join(','), String(d.subject || 'Alerta de pantallas').slice(0, 200), String(d.body || '').slice(0, 5000), { name: 'Pantallas La Cole' });
    return salida({ ok: true });
  } catch (err) {
    return salida({ ok: false, error: String(err) });
  }
}

function salida(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
