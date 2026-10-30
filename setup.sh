#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  SETUP AUTOMÁTICO — Golden Dragon XML6609 Dashboard en Termux
#  Uso:  bash setup.sh
#  Ejecutar en Termux (radio Android) para descargar y servir el dashboard
# ============================================================

set -e

echo "======================================"
echo "  🚌  GOLDEN DRAGON XML6609 DASHBOARD"
echo "  Instalación automática para Termux"
echo "======================================"
echo ""

# 1. Verificar que estamos en Termux
if [ ! -d "/data/data/com.termux" ] && [ "$PREFIX" != "/data/data/com.termux/files/usr" ]; then
  echo "⚠️  Este script debe ejecutarse en Termux (Android)."
  echo "    Si está en otra terminal, instale Termux desde F-Droid primero."
  echo ""
  read -p "¿Continuar de todos modos? [s/N]: " resp
  if [ "$resp" != "s" ] && [ "$resp" != "S" ]; then
    exit 1
  fi
fi

# 2. Actualizar repositorios
echo "📦  Actualizando repositorios de Termux..."
pkg update -y > /dev/null 2>&1 || echo "   (pkg update avisó, continuando)"
pkg upgrade -y > /dev/null 2>&1 || true

# 3. Instalar dependencias
echo "🔧  Instalando git y python..."
pkg install -y git python > /dev/null 2>&1 || {
  echo "   Intentando con apt..."
  apt-get update -y > /dev/null 2>&1 || true
  apt-get install -y git python3 > /dev/null 2>&1 || true
}

# 4. Clonar el repo
echo "📥  Descargando el dashboard..."
if [ -d "golden-launcher" ]; then
  echo "   Ya existe la carpeta golden-launcher. Actualizando..."
  cd golden-launcher
  git pull --ff-only 2>/dev/null || echo "   (no se pudo actualizar, usando lo existente)"
else
  git clone https://github.com/xavnive-sketch/golden-launcher.git
  cd golden-launcher
fi

echo ""
echo "✅  Dashboard descargado en: $(pwd)"
echo ""

# 5. Iniciar el servidor
echo "🚀  Iniciando servidor en el puerto 8080..."
echo "    (Deje esta ventana abierta. No la cierre.)"
echo ""
echo "    Abra el navegador de la radio y vaya a:"
echo ""
echo "    ┌────────────────────────────────────┐"
echo "    │   http://localhost:8080            │"
echo "    └────────────────────────────────────┘"
echo ""
echo "    Ctrl+C para detener."
echo ""

# Servir en el puerto 8080 (usar python3 si existe, si no python)
if command -v python3 > /dev/null 2>&1; then
  python3 -m http.server 8080
else
  python -m http.server 8080
fi
