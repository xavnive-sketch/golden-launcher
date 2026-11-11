// Configuración de Tailwind. El CSS ya compilado está en vendor/tailwind.css.
// Si cambias clases en index.html, regenéralo con:
//   npx tailwindcss@3.4.16 -i tailwind.in.css -o vendor/tailwind.css --minify
module.exports = {
    content: ['./index.html', './bus3d.js'],
    theme: {
        extend: {
            colors: {
                dash: {
                    bg: '#010610',
                    panel: '#051325',
                    border: '#0d2d4d',
                    cyan: '#00f0ff',
                    cyanDark: '#008ba3',
                    green: '#00ffaa',
                    amber: '#ffb700',
                    muted: '#4f759b',
                    text: '#d0e8ff'
                }
            },
            fontFamily: {
                orbitron: ['Orbitron', 'sans-serif'],
                rajdhani: ['Rajdhani', 'sans-serif'],
            }
        }
    }
};
