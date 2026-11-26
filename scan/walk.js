// Paseo en primera persona.
// Táctil: joystick virtual en la mitad izquierda (moverse), arrastre en el resto (mirar).
// Escritorio: WASD / flechas para moverse, arrastrar con el ratón para mirar.

const JOY_RADIUS = 60;     // px
const LOOK_SPEED = 0.005;  // rad por px
const PITCH_LIMIT = 1.4;   // rad

export class WalkControls {
  constructor(camera, dom, joyEl, knobEl) {
    this.camera = camera;
    this.dom = dom;
    this.joyEl = joyEl;
    this.knobEl = knobEl;
    this.enabled = false;
    this.speed = 1.3;        // m/s
    this.yaw = 0;
    this.pitch = 0;
    this.joy = null;         // { id, x0, y0, dx, dy }
    this.look = null;        // { id, x, y }
    this.keys = new Set();
    this.collider = null;    // { move(x, z, dx, dz) -> [x, z] } o null (sin colisiones)
    this.collide = true;

    dom.addEventListener('pointerdown', (e) => this._down(e));
    addEventListener('pointermove', (e) => this._move(e));
    addEventListener('pointerup', (e) => this._up(e));
    addEventListener('pointercancel', (e) => this._up(e));
    addEventListener('keydown', (e) => { if (this.enabled) this.keys.add(e.code); });
    addEventListener('keyup', (e) => this.keys.delete(e.code));
  }

  start(position, yaw = 0) {
    this.camera.position.copy(position);
    this.yaw = yaw;
    this.pitch = 0;
    this.enabled = true;
    this._apply();
  }

  stop() {
    this.enabled = false;
    this.joy = null;
    this.look = null;
    this.keys.clear();
    this.joyEl.hidden = true;
  }

  update(dt) {
    if (!this.enabled) return;
    let fwd = 0, side = 0;
    if (this.joy) { fwd = -this.joy.dy / JOY_RADIUS; side = this.joy.dx / JOY_RADIUS; }
    if (this.keys.has('KeyW') || this.keys.has('ArrowUp')) fwd += 1;
    if (this.keys.has('KeyS') || this.keys.has('ArrowDown')) fwd -= 1;
    if (this.keys.has('KeyD') || this.keys.has('ArrowRight')) side += 1;
    if (this.keys.has('KeyA') || this.keys.has('ArrowLeft')) side -= 1;
    const len = Math.hypot(fwd, side);
    if (len > 1) { fwd /= len; side /= len; }
    const s = this.speed * dt;
    const sin = Math.sin(this.yaw), cos = Math.cos(this.yaw);
    // adelante = -Z rotado por yaw; derecha = +X rotado por yaw
    const dx = (-sin * fwd + cos * side) * s;
    const dz = (-cos * fwd - sin * side) * s;
    const p = this.camera.position;
    if (this.collide && this.collider) {
      [p.x, p.z] = this.collider.move(p.x, p.z, dx, dz);
    } else {
      p.x += dx; p.z += dz;
    }
    this._apply();
  }

  _apply() {
    this.camera.rotation.set(this.pitch, this.yaw, 0, 'YXZ');
  }

  _down(e) {
    if (!this.enabled) return;
    const touchLike = e.pointerType !== 'mouse';
    if (touchLike && !this.joy && e.clientX < innerWidth * 0.45) {
      this.joy = { id: e.pointerId, x0: e.clientX, y0: e.clientY, dx: 0, dy: 0 };
      this.joyEl.style.left = `${e.clientX}px`;
      this.joyEl.style.top = `${e.clientY}px`;
      this.knobEl.style.transform = 'translate(-50%, -50%)';
      this.joyEl.hidden = false;
    } else if (!this.look) {
      this.look = { id: e.pointerId, x: e.clientX, y: e.clientY };
    } else {
      return;
    }
    this.dom.setPointerCapture?.(e.pointerId);
    e.preventDefault();
  }

  _move(e) {
    if (this.joy && e.pointerId === this.joy.id) {
      let dx = e.clientX - this.joy.x0, dy = e.clientY - this.joy.y0;
      const len = Math.hypot(dx, dy);
      if (len > JOY_RADIUS) { dx *= JOY_RADIUS / len; dy *= JOY_RADIUS / len; }
      this.joy.dx = dx; this.joy.dy = dy;
      this.knobEl.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
    } else if (this.look && e.pointerId === this.look.id) {
      this.yaw -= (e.clientX - this.look.x) * LOOK_SPEED;
      this.pitch -= (e.clientY - this.look.y) * LOOK_SPEED;
      this.pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, this.pitch));
      this.look.x = e.clientX; this.look.y = e.clientY;
    }
  }

  _up(e) {
    if (this.joy && e.pointerId === this.joy.id) { this.joy = null; this.joyEl.hidden = true; }
    if (this.look && e.pointerId === this.look.id) this.look = null;
  }
}
