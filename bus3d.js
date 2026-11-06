/* bus3d.js v3 · Modelo 3D de la furgoneta Golden Dragon XML6609 (familia X5) para el dashboard
   Proporciones de la familia X5 / Higer H5C: ~6.0 m largo · ~2.0 m ancho · ~2.6 m techo alto · entre ejes 3.67 m
   - Arrastra para girar · TOCA el vehículo para alternar REALISTA <-> HOLOGRAMA (con etiquetas en vivo)
   - Las ruedas giran y la carrocería vibra según la velocidad (#val-speed); el motor muestra #val-temp */
(function () {
  const THREE_SRC = 'https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js';
  const GLTF_SRC = 'https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/loaders/GLTFLoader.js';
  const GLB_URL = 'assets/autoline_bus.glb';

  function boot() {
    const box = document.querySelector('.bus-3d-box') || document.querySelector('div.relative.w-full');
    if (!box) return;
    if (window.THREE) return build(box);
    const s = document.createElement('script');
    s.src = THREE_SRC;
    s.onload = () => build(box);
    s.onerror = () => console.warn('[bus3d] no se pudo cargar three.js (¿sin internet?)');
    document.head.appendChild(s);
  }

  function build(box) {
    const T = THREE;
    const CY = 0x00f0ff, AM = 0xffb700;
    const XF = 2.1, XR = -1.57, WR = 0.38; // eje delantero, eje trasero, radio de rueda
    const BODY_REAL = 0xe3ebf3, BODY_HOLO = 0x16a6c9;

    const canvas = document.createElement('canvas');
    canvas.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;touch-action:none;cursor:grab;z-index:5';
    box.insertBefore(canvas, box.firstChild);

    const renderer = new T.WebGLRenderer({ canvas, antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 3));
    const scene = new T.Scene();
    const camera = new T.PerspectiveCamera(32, 1, 0.1, 100);
    camera.position.set(0, 2.6, 7.5);
    camera.lookAt(0, 1.3, 0);

    scene.add(new T.AmbientLight(0x9cc4ff, 0.6));
    const sun = new T.DirectionalLight(0xffffff, 1.0); sun.position.set(5, 8, 6); scene.add(sun);
    const rimL = new T.PointLight(CY, 1.3, 14); rimL.position.set(-4, 2, -4); scene.add(rimL);

    // Entorno para reflejos (cielo claro arriba, horizonte cian, suelo oscuro)
    const face = (top, bot) => {
      const c = document.createElement('canvas'); c.width = c.height = 64;
      const x = c.getContext('2d'), g = x.createLinearGradient(0, 0, 0, 64);
      g.addColorStop(0, top); g.addColorStop(1, bot); x.fillStyle = g; x.fillRect(0, 0, 64, 64);
      return c;
    };
    const side = face('#2f6a8c', '#050d16');
    const env = new T.CubeTexture([side, side, face('#9fe0ff', '#2f6a8c'), face('#02060c', '#02060c'), side, side]);
    env.needsUpdate = true;

    // Materiales
    const bodyMat = new T.MeshStandardMaterial({ color: BODY_REAL, metalness: 0.45, roughness: 0.3, emissive: 0x0a1624, envMap: env, envMapIntensity: 0.9 });
    const glassMat = new T.MeshStandardMaterial({ color: 0x1a4a66, metalness: 0.9, roughness: 0.05, transparent: true, opacity: 0.9, emissive: 0x0a3550, side: T.DoubleSide, envMap: env, envMapIntensity: 1.5 });
    const darkMat = new T.MeshStandardMaterial({ color: 0x0a121c, metalness: 0.25, roughness: 0.75, side: T.DoubleSide });
    const chromeMat = new T.MeshStandardMaterial({ color: 0xcfd8e0, metalness: 1, roughness: 0.2, envMap: env });
    const glow = (c) => new T.MeshBasicMaterial({ color: c });
    const edgeMat = new T.LineBasicMaterial({ color: CY, transparent: true, opacity: 0.4 });

    const bus = new T.Group();
    scene.add(bus);
    bus.visible = false; // ocultar el modelo procedural antiguo; el GLB real lo mostrará al cargar

    // Perfil lateral (frente hacia +x). Los arcos de rueda están cortados en el perfil.
    const s = new T.Shape();
    s.moveTo(-3.0, 0.28);
    [XR, XF].forEach((ax) => {
      s.lineTo(ax - 0.47, 0.28);
      s.absarc(ax, WR, 0.47, Math.PI, 0, true);
      s.lineTo(ax + 0.47, 0.28);
    });
    s.lineTo(3.0, 0.28);
    s.lineTo(3.0, 0.95);                       // frente: parachoques + parrilla
    s.quadraticCurveTo(3.0, 1.06, 2.88, 1.09); // borde del capó
    s.lineTo(2.35, 1.14);                      // capó corto
    s.lineTo(1.75, 2.4);                       // parabrisas muy inclinado
    s.quadraticCurveTo(1.68, 2.55, 1.5, 2.6);  // borde del techo
    s.lineTo(-2.85, 2.6);                      // techo alto plano
    s.quadraticCurveTo(-3.0, 2.6, -3.0, 2.45);
    s.lineTo(-3.0, 0.28);
    // Bordes redondeados: 1.88 de profundidad + 2 x 0.06 de bisel = 2.0 m de ancho
    const geo = new T.ExtrudeGeometry(s, { depth: 1.88, bevelEnabled: true, bevelThickness: 0.06, bevelSize: 0.04, bevelSegments: 3, curveSegments: 10 });
    geo.translate(0, 0, -0.94);
    const bodyMesh = new T.Mesh(geo, bodyMat);
    bus.add(bodyMesh);
    // Contorno cian del perfil en ambos costados
    const pts = s.getPoints(12);
    [1, -1].forEach((sd) => {
      const g = new T.BufferGeometry().setFromPoints(pts.map((p) => new T.Vector3(p.x, p.y, sd * 1.006)));
      bus.add(new T.Line(g, edgeMat));
    });

    const add = (mesh, x, y, z, ry) => { mesh.position.set(x, y, z); if (ry) mesh.rotation.y = ry; bus.add(mesh); return mesh; };
    const boxM = (w, h, d, mat) => new T.Mesh(new T.BoxGeometry(w, h, d), mat);
    const poly = (p, mat) => {
      const sh = new T.Shape();
      p.forEach((q, i) => (i ? sh.lineTo(q[0], q[1]) : sh.moveTo(q[0], q[1])));
      return new T.Mesh(new T.ShapeGeometry(sh), mat);
    };
    function tex(txt, w, h, font, color, bg, spacing) {
      const c = document.createElement('canvas'); c.width = w; c.height = h;
      const x = c.getContext('2d');
      if (bg) { x.fillStyle = bg; x.fillRect(0, 0, w, h); }
      x.font = font; x.fillStyle = color; x.textAlign = 'center'; x.textBaseline = 'middle';
      if ('letterSpacing' in x) x.letterSpacing = (spacing || 0) + 'px';
      x.fillText(txt, w / 2, h / 2);
      return new T.CanvasTexture(c);
    }
    const nameMat = new T.MeshBasicMaterial({ map: tex('GOLDEN DRAGON', 512, 80, 'bold 46px Orbitron, Arial, sans-serif', '#c98a00', null, 4), transparent: true });

    // Relleno oscuro dentro de los arcos de rueda
    [XF, XR].forEach((ax) => add(boxM(0.96, 0.62, 1.5, darkMat), ax, 0.54, 0));

    // Costados (derecho +z con puerta corrediza y estribo; izquierdo -z espejo)
    [1, -1].forEach((sd) => {
      const z = sd * 1.004;
      const P = (p, mat) => { const m = poly(p, mat); m.position.z = z; bus.add(m); return m; };
      P([[0.95, 1.55], [2.12, 1.55], [1.8, 2.25], [0.95, 2.25]], glassMat);      // ventana puerta delantera
      P([[-0.85, 1.55], [0.55, 1.55], [0.55, 2.25], [-0.85, 2.25]], glassMat);   // ventana puerta corrediza
      P([[-1.95, 1.55], [-1.1, 1.55], [-1.1, 2.25], [-1.95, 2.25]], glassMat);   // ventanas traseras
      P([[-2.85, 1.55], [-2.0, 1.55], [-2.0, 2.15], [-2.85, 2.15]], glassMat);
      [0.75, -1.05].forEach((x) => add(boxM(0.014, 2.0, 0.012, darkMat), x, 1.45, sd * 1.006)); // costuras de puertas
      add(boxM(4.9, 0.07, 0.012, glow(AM)), -0.45, 1.42, sd * 1.006);   // franja dorada
      add(boxM(4.9, 0.025, 0.012, glow(AM)), -0.45, 1.32, sd * 1.006);
      add(boxM(2.7, 0.06, 0.014, darkMat), 0.25, 0.78, sd * 1.007);     // moldura lateral
      add(boxM(2.6, 0.12, 0.012, darkMat), 0.25, 0.36, sd * 1.006);     // zócalo
      [0.5, 1.1].forEach((x) => add(boxM(0.16, 0.035, 0.03, chromeMat), x, 1.2, sd * 1.015)); // manijas
      // espejo con brazo
      add(boxM(0.05, 0.04, 0.2, darkMat), 2.04, 1.55, sd * 1.09);
      add(boxM(0.09, 0.28, 0.14, darkMat), 2.02, 1.68, sd * 1.2);
      // luz de giro lateral
      add(boxM(0.1, 0.035, 0.02, glow(AM)), 2.5, 0.98, sd * 1.01);
      add(new T.Mesh(new T.PlaneGeometry(1.4, 0.22), nameMat), -0.25, 1.0, sd * 1.012, sd > 0 ? 0 : Math.PI);
    });
    add(boxM(1.4, 0.05, 0.2, darkMat), -0.25, 0.3, 1.1); // estribo bajo la puerta corrediza

    // Parabrisas con limpiaparabrisas
    const ws = boxM(0.03, 1.25, 1.78, glassMat);
    ws.rotation.z = 0.444; add(ws, 2.1, 1.794, 0);
    [0.35, -0.35].forEach((z, i) => {
      const w = boxM(0.012, 0.03, 0.8, darkMat);
      w.position.set(0.02, -0.5, z); w.rotation.x = i ? -0.18 : 0.18; ws.add(w);
    });

    // Frente: faros angulares con haz, parrilla, defensa, antinieblas, placa
    const beamMat = new T.MeshBasicMaterial({ color: 0xcfefff, transparent: true, opacity: 0.13, blending: T.AdditiveBlending, depthWrite: false, side: T.DoubleSide });
    [0.72, -0.72].forEach((z) => {
      add(boxM(0.05, 0.22, 0.55, glow(0xdff8ff)), 3.06, 0.85, z);
      add(boxM(0.05, 0.07, 0.3, glow(AM)), 3.06, 0.64, z);
      add(boxM(0.05, 0.08, 0.14, glow(0xfff2c0)), 3.06, 0.45, z * 1.15);
      const cone = new T.Mesh(new T.ConeGeometry(0.4, 2.6, 16, 1, true), beamMat);
      cone.rotation.z = Math.PI / 2; add(cone, 4.35, 0.85, z);
    });
    add(boxM(0.05, 0.24, 0.62, darkMat), 3.055, 0.84, 0);
    add(new T.Mesh(new T.CircleGeometry(0.07, 20), glow(AM)), 3.09, 0.86, 0, Math.PI / 2);
    add(boxM(0.14, 0.2, 1.96, darkMat), 3.0, 0.42, 0);
    add(new T.Mesh(new T.PlaneGeometry(0.5, 0.12), new T.MeshBasicMaterial({ map: tex('XML6609', 256, 64, 'bold 40px Arial', '#06101c', '#d8f6ff') })), 3.075, 0.42, 0, Math.PI / 2);

    // Trasera: luneta, luces verticales, defensa, emblema
    add(boxM(0.02, 0.7, 1.5, glassMat), -3.05, 1.95, 0);
    [0.88, -0.88].forEach((z) => add(boxM(0.04, 0.6, 0.12, glow(0xff3b3b)), -3.06, 1.0, z));
    add(boxM(0.12, 0.22, 1.98, darkMat), -3.0, 0.42, 0);
    add(boxM(0.012, 1.9, 0.014, darkMat), -3.055, 1.45, 0);
    add(new T.Mesh(new T.CircleGeometry(0.07, 20), glow(AM)), -3.07, 1.35, 0, -Math.PI / 2);

    // Techo: ventila y antena GPS
    const vent = add(boxM(0.55, 0.07, 0.55, darkMat), -1.0, 2.67, 0);
    vent.add(new T.LineSegments(new T.EdgesGeometry(vent.geometry), edgeMat));
    add(boxM(0.28, 0.09, 0.1, darkMat), -2.2, 2.68, 0);
    const beacon = add(new T.Mesh(new T.SphereGeometry(0.06, 12, 12), glow(CY)), -2.2, 2.76, 0);

    // Ruedas (llanta cromada + radios cian que se ven girar)
    const wheels = [];
    [[XF, 1], [XF, -1], [XR, 1], [XR, -1]].forEach(([x, sd]) => {
      const g = new T.Group(); g.position.set(x, WR, sd * 0.88);
      const tire = new T.Mesh(new T.CylinderGeometry(WR, WR, 0.24, 28), darkMat); tire.rotation.x = Math.PI / 2;
      const disc = new T.Mesh(new T.CylinderGeometry(0.25, 0.25, 0.25, 20), chromeMat); disc.rotation.x = Math.PI / 2;
      const spokes = new T.Mesh(new T.CylinderGeometry(0.23, 0.23, 0.27, 5), new T.MeshBasicMaterial({ color: CY, wireframe: true })); spokes.rotation.x = Math.PI / 2;
      const hub = new T.Mesh(new T.CylinderGeometry(0.07, 0.07, 0.29, 10), glow(CY)); hub.rotation.x = Math.PI / 2;
      g.add(tire, disc, spokes, hub, new T.Mesh(new T.TorusGeometry(WR, 0.025, 8, 28), glow(0x1d6fa5)));
      bus.add(g); wheels.push(g);
    });

    // Haz de escaneo holográfico y sombra
    const scan = boxM(6.2, 0.02, 2.2, new T.MeshBasicMaterial({ color: CY, transparent: true, opacity: 0.2, depthWrite: false }));
    bus.add(scan);
    const shadow = new T.Mesh(new T.CircleGeometry(3.2, 32), new T.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.16, depthWrite: false }));
    shadow.rotation.x = -Math.PI / 2; shadow.scale.y = 0.5; shadow.position.y = 0.012; bus.add(shadow);

    // Suelo
    const grid = new T.GridHelper(12, 12, CY, 0x0d4a6e);
    grid.material.transparent = true; grid.material.opacity = 0.35; scene.add(grid);
    [[4.0, 4.1, 0.75], [3.6, 3.65, 0.35]].forEach(([a, b, o]) => {
      const r = new T.Mesh(new T.RingGeometry(a, b, 64), new T.MeshBasicMaterial({ color: CY, transparent: true, opacity: o, side: T.DoubleSide }));
      r.rotation.x = -Math.PI / 2; r.position.y = 0.01; scene.add(r);
    });

    // Etiquetas del modo holograma (HTML sobre el canvas, siguen al punto 3D)
    const ov = document.createElement('div');
    ov.style.cssText = 'position:absolute;inset:0;pointer-events:none;z-index:15;overflow:hidden;display:none';
    box.appendChild(ov);
    const spots = [
      { p: new T.Vector3(2.6, 1.1, 0), txt: () => 'MOTOR ' + ((document.getElementById('val-temp') || {}).textContent || 'OK') },
      { p: new T.Vector3(-2.2, 2.76, 0), txt: () => 'GPS · 4G' },
      { p: new T.Vector3(XF, WR, 1.0), txt: () => 'FRENOS · ABS' },
      { p: new T.Vector3(-0.2, 2.0, 1.0), txt: () => '18 PAX' }
    ].map((o) => {
      const el = document.createElement('div');
      el.style.cssText = 'position:absolute;transform:translate(-50%,-130%);font:700 7px Orbitron,Arial,sans-serif;letter-spacing:.05em;color:#00ffaa;background:rgba(1,6,16,.88);border:1px solid rgba(0,255,170,.6);padding:1px 4px;border-radius:3px;white-space:nowrap';
      ov.appendChild(el); o.el = el; return o;
    });
    const hint = document.createElement('div');
    hint.textContent = 'ARRASTRA · TOCA = HOLO';
    hint.style.cssText = 'position:absolute;top:4px;left:6px;z-index:16;pointer-events:none;font:700 7px Orbitron,Arial,sans-serif;letter-spacing:.08em;color:#4f759b;transition:opacity 1s';
    box.appendChild(hint);
    setTimeout(() => { hint.style.opacity = '0'; }, 6000);

    let holo = false;
    function setHolo(on) {
      holo = on;
      bodyMat.color.set(on ? BODY_HOLO : BODY_REAL);
      bodyMat.emissive.set(on ? 0x063a52 : 0x0a1624);
      bodyMat.transparent = on; bodyMat.opacity = on ? 0.3 : 1; bodyMat.depthWrite = !on;
      bodyMat.needsUpdate = true;
      edgeMat.opacity = on ? 0.95 : 0.4;
      ov.style.display = on ? 'block' : 'none';
      if (usingGLB) applyHolo(on);
    }

    // Giro con el dedo / mouse + toque corto para cambiar de modo
    let drag = false, lastX = 0, vel = 0, downX = 0, downT = 0;
    canvas.addEventListener('pointerdown', (e) => { drag = true; lastX = downX = e.clientX; downT = performance.now(); canvas.setPointerCapture(e.pointerId); canvas.style.cursor = 'grabbing'; });
    canvas.addEventListener('pointermove', (e) => { if (!drag) return; const dx = e.clientX - lastX; lastX = e.clientX; vel = dx * 0.012; bus.rotation.y += vel; });
    canvas.addEventListener('pointerup', (e) => {
      drag = false; canvas.style.cursor = 'grab';
      if (Math.abs(e.clientX - downX) < 6 && performance.now() - downT < 350) { vel = 0; setHolo(!holo); }
    });
    canvas.addEventListener('pointercancel', () => { drag = false; canvas.style.cursor = 'grab'; });

    function resize() {
      const r = box.getBoundingClientRect();
      const w = Math.max(r.width, 10), h = Math.max(r.height, 10);
      renderer.setSize(w, h, false);
      camera.aspect = w / h; camera.updateProjectionMatrix();
    }
    resize();
    window.addEventListener('resize', resize);
    if (window.ResizeObserver) new ResizeObserver(resize).observe(box);

    // ---- Modelo real (GLB) · Autoline ----
    let usingGLB = false, glbMats = [];
    function applyHolo(on) {
      glbMats.forEach((e) => { e.mesh.material = on ? e.holo : e.orig; });
    }
    function loadRealModel() {
      const fail = (why) => console.warn('[bus3d] modelo GLB no disponible (' + why + '); se usa el procedural');
      if (!T.GLTFLoader) {
        const s2 = document.createElement('script');
        s2.src = GLTF_SRC;
        s2.onload = fetchModel; s2.onerror = () => fail('loader');
        document.head.appendChild(s2);
      } else fetchModel();
      function fetchModel() {
        if (!T.GLTFLoader) return fail('loader');
        new T.GLTFLoader().load(GLB_URL, (gltf) => {
          const root = gltf.scene;
          root.updateMatrixWorld(true);
          const b3 = new T.Box3().setFromObject(root);
          const size = new T.Vector3(); b3.getSize(size);
          const center = new T.Vector3(); b3.getCenter(center);
          const LEN = 6.0;
          const s = LEN / Math.max(size.x, size.z, 0.001);
          root.position.sub(center);
          if (size.z > size.x) root.rotation.y = Math.PI / 2;
          const g = new T.Group(); g.add(root);
          g.scale.setScalar(s);
          g.position.y = (size.y / 2) * s + 0.04;
          bus.children.slice().forEach((c) => { if (c !== scan && c !== shadow && c !== beacon) bus.remove(c); });
          bus.add(g);
          bus.visible = true; // modelo real cargado: mostrar
          usingGLB = true;
          g.traverse((o) => {
            if (o.isMesh) {
              const m = o.material;
              // el GLB viene con metallic=1 (negro sin entorno): darle el envMap del dashboard y pintura legible
              m.envMap = env; m.envMapIntensity = 0.8;
              m.metalness = 0.25; m.roughness = 0.55;
              m.needsUpdate = true;
              const holo = new T.MeshStandardMaterial({ color: 0x16a6c9, transparent: true, opacity: 0.35, emissive: 0x063a52, metalness: 0.3, roughness: 0.4, side: T.DoubleSide });
              glbMats.push({ mesh: o, orig: m, holo });
            }
          });
          const roofY = size.y * s;
          spots[0].p.set(2.4, roofY * 0.45, 0);
          spots[1].p.set(-2.0, roofY - 0.15, 0);
          spots[2].p.set(0.9, 0.45, 0.75);
          spots[3].p.set(-0.3, roofY * 0.6, 0.75);
          beacon.position.set(-2.0, roofY + 0.08, 0);
          console.log('[bus3d] modelo GLB cargado ·', size.x.toFixed(2) + 'x' + size.y.toFixed(2) + 'x' + size.z.toFixed(2), '· escala ' + s.toFixed(3));
          // Encuadrar el bus COMPLETO en el viewport (se vea entero en cualquier pantalla)
          requestAnimationFrame(() => {
            const bb = new T.Box3().setFromObject(bus);
            const bs = bb.getSize(new T.Vector3());
            const bc = bb.getCenter(new T.Vector3());
            const r0 = box.getBoundingClientRect();
            const aspect = Math.max(r0.width, 10) / Math.max(r0.height, 10);
            const fov = camera.fov * Math.PI / 180;
            const dist = Math.max(bs.y / 2 / Math.tan(fov / 2), bs.x / 2 / (Math.tan(fov / 2) * aspect)) * 1.3;
            camera.position.set(0, bc.y + bs.y * 0.25, bc.z + dist);
            camera.lookAt(bc.x, bc.y, bc.z);
            camera.updateProjectionMatrix();
            console.log('[bus3d] encuadre: dist ' + dist.toFixed(2) + ' · bus ' + bs.x.toFixed(1) + 'x' + bs.y.toFixed(1) + 'x' + bs.z.toFixed(1));
          });
        }, undefined, () => fail('glb'));
      }
    }
    loadRealModel();

    bus.rotation.y = -0.7;
    const v = new T.Vector3();
    let last = performance.now(), lastTxt = 0;
    function frame(now) {
      requestAnimationFrame(frame);
      const dt = Math.min((now - last) / 1000, 0.05); last = now;
      if (box.offsetParent === null) return; // página oculta: no gastar batería
      if (!drag) { bus.rotation.y += vel + 0.3 * dt; vel *= 0.94; }
      const el = document.getElementById('val-speed');
      const kmh = el ? parseFloat(el.textContent) : NaN;
      const sp = isNaN(kmh) ? 25 : kmh;
      if (!usingGLB) wheels.forEach((w) => { w.rotation.z -= sp * 0.12 * dt; });
      bus.position.y = 0.012 * Math.sin(now / 90) * Math.min(sp / 80, 1); // vibración leve al rodar
      scan.position.y = 0.4 + ((now / 2600) % 1) * 2.2;
      scan.material.opacity = 0.26 * (1 - Math.abs(scan.position.y - 1.5) / 1.4);
      beacon.scale.setScalar(0.8 + 0.4 * Math.abs(Math.sin(now / 350)));
      renderer.render(scene, camera);
      if (holo) {
        bus.updateMatrixWorld();
        spots.forEach((o) => {
          v.copy(o.p); bus.localToWorld(v); v.project(camera);
          o.el.style.left = ((v.x + 1) / 2 * 100) + '%';
          o.el.style.top = ((1 - v.y) / 2 * 100) + '%';
        });
        if (now - lastTxt > 500) { lastTxt = now; spots.forEach((o) => { o.el.textContent = '● ' + o.txt(); }); }
      }
    }
    requestAnimationFrame(frame);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
