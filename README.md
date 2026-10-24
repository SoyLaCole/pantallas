# Pantallas La Cole

Sistema para publicar videos, imágenes y páginas web en los televisores de la institución.

- `server/` + `Dockerfile`: panel de administración y servidor (se despliega en el VPS con Coolify).
- `android/`: app "Pantallas La Cole" para los TV (se compila con Android Studio).

## Variables de entorno del servidor (se configuran en Coolify, nunca en el código)

| Variable | Para qué |
|---|---|
| `BASE_URL` | `https://pantallas.lacole.edu.co` |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Credenciales de inicio de sesión con Google |
| `ADMIN_EMAILS` | Correos autorizados, separados por coma |
| `ALLOWED_DOMAIN` | `lacole.edu.co` |
| `SECRET_KEY` | Texto largo aleatorio (firma las sesiones) |
| `TZ_NAME` | `America/Bogota` |
| `MAX_UPLOAD_MB` | Tamaño máximo por archivo (300) |

Volumen persistente: `/data` (base de datos y archivos subidos). Puerto: `8000`.
