import { Component, DestroyRef, ElementRef, computed, inject, input } from '@angular/core';

interface Cube {
  /** Edge length in px. */
  size: number;
  /** Position in % of the screen. */
  x: number;
  y: number;
  /** Seconds for one full turn, and the start offset, so the cubes don't move in step. */
  spin: number;
  delay: number;
}

/**
 * A decorative 3D scene behind a page: translucent orange cubes turning and floating over a perspective grid that
 * moves toward the viewer, tilting slightly with the mouse. Pure CSS 3D (no library). Hidden from screen readers,
 * never catches clicks, and stands still when the system asks for reduced motion.
 *
 * It sits at z-index -1, so the page's own content needs no changes: the page's root element only needs
 * "isolation: isolate" (it then paints its background, the scene, and its content, in that order).
 * calm = fewer, fainter, slower cubes, for pages people read and work on (inside the app).
 */
@Component({
  selector: 'app-auth-backdrop',
  host: { 'aria-hidden': 'true', '[class.calm]': 'calm()' },
  template: `
    <div class="glow one"></div>
    <div class="glow two"></div>
    <div class="scene">
      <div class="floor"></div>
      @for (cube of shown(); track $index) {
        <div class="float"
             [style.left.%]="cube.x" [style.top.%]="cube.y"
             [style.--size.px]="cube.size" [style.--spin.s]="cube.spin" [style.--delay.s]="cube.delay">
          <div class="cube">
            <span class="face front"></span>
            <span class="face back"></span>
            <span class="face right"></span>
            <span class="face left"></span>
            <span class="face top"></span>
            <span class="face bottom"></span>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      position: fixed;
      inset: 0;
      z-index: -1;
      overflow: hidden;
      pointer-events: none;
      --tilt-x: 0deg;
      --tilt-y: 0deg;
    }

    .glow {
      position: absolute;
      width: 46vmax;
      height: 46vmax;
      border-radius: 50%;
      filter: blur(70px);
      opacity: 0.55;
      background: radial-gradient(circle, color-mix(in srgb, var(--dh-accent) 45%, transparent), transparent 70%);
      animation: drift 18s ease-in-out infinite alternate;
    }
    .glow.one { left: -12vmax; top: -14vmax; }
    .glow.two { right: -14vmax; bottom: -16vmax; animation-delay: -9s; }

    .scene {
      position: absolute;
      inset: 0;
      perspective: 900px;
      transform-style: preserve-3d;
      transform: rotateX(var(--tilt-x)) rotateY(var(--tilt-y));
      transition: transform 0.6s ease-out;
    }

    /* The grid floor: a big plane laid back in 3D, its lines scrolling toward the viewer. */
    .floor {
      position: absolute;
      left: -50%;
      right: -50%;
      bottom: -30%;
      height: 90%;
      transform: rotateX(72deg);
      transform-origin: bottom center;
      background-image:
        linear-gradient(color-mix(in srgb, var(--dh-accent) 50%, transparent) 1px, transparent 1px),
        linear-gradient(90deg, color-mix(in srgb, var(--dh-accent) 50%, transparent) 1px, transparent 1px);
      background-size: 64px 64px;
      mask-image: linear-gradient(to top, black 20%, transparent 90%);
      animation: roll 6s linear infinite;
    }

    .float {
      position: absolute;
      width: var(--size);
      height: var(--size);
      transform-style: preserve-3d;
      animation: bob 7s ease-in-out infinite;
      animation-delay: var(--delay);
    }

    .cube {
      position: relative;
      width: 100%;
      height: 100%;
      transform-style: preserve-3d;
      animation: spin var(--spin) linear infinite;
      animation-delay: var(--delay);
    }

    .face {
      position: absolute;
      inset: 0;
      border: 1.5px solid color-mix(in srgb, var(--dh-accent) 75%, transparent);
      border-radius: 6px;
      background: color-mix(in srgb, var(--dh-accent) 12%, transparent);
      box-shadow: inset 0 0 22px color-mix(in srgb, var(--dh-accent) 25%, transparent);
    }
    .front  { transform: translateZ(calc(var(--size) / 2)); }
    .back   { transform: rotateY(180deg) translateZ(calc(var(--size) / 2)); }
    .right  { transform: rotateY(90deg) translateZ(calc(var(--size) / 2)); }
    .left   { transform: rotateY(-90deg) translateZ(calc(var(--size) / 2)); }
    .top    { transform: rotateX(90deg) translateZ(calc(var(--size) / 2));
              background: color-mix(in srgb, var(--dh-accent) 22%, transparent); }
    .bottom { transform: rotateX(-90deg) translateZ(calc(var(--size) / 2)); }

    @keyframes spin {
      from { transform: rotateX(0deg) rotateY(0deg); }
      to { transform: rotateX(360deg) rotateY(720deg); }
    }
    @keyframes bob {
      0%, 100% { transform: translateY(0) translateZ(0); }
      50% { transform: translateY(-26px) translateZ(40px); }
    }
    @keyframes roll {
      from { background-position: 0 0; }
      to { background-position: 0 64px; }
    }
    @keyframes drift {
      from { transform: translate(0, 0) scale(1); }
      to { transform: translate(6vmax, 4vmax) scale(1.15); }
    }

    :host(.calm) { opacity: 0.45; }
    :host(.calm) .floor { opacity: 0.6; }

    /* Phones: fewer, smaller cubes away from the form. */
    @media (max-width: 640px) {
      .float:nth-child(n + 6) { display: none; }
      .float { scale: 0.7; }
    }

    @media (prefers-reduced-motion: reduce) {
      .glow, .floor, .float, .cube { animation: none; }
      .cube { transform: rotateX(-24deg) rotateY(36deg); }
      .scene { transition: none; }
    }
  `,
})
export class AuthBackdropComponent {
  readonly calm = input(false);
  /** Only the cubes near the left and right edges, for wide pages whose text spans the middle (landing). */
  readonly edgesOnly = input(false);
  /** Placed around the edges, so the login card in the middle stays clear. */
  protected readonly cubes: Cube[] = [
    { size: 90, x: 9, y: 16, spin: 22, delay: 0 },
    { size: 54, x: 24, y: 62, spin: 16, delay: -4 },
    { size: 120, x: 78, y: 12, spin: 28, delay: -9 },
    { size: 64, x: 86, y: 58, spin: 18, delay: -2 },
    { size: 40, x: 66, y: 80, spin: 14, delay: -6 },
    { size: 46, x: 14, y: 84, spin: 20, delay: -11 },
    { size: 34, x: 40, y: 8, spin: 12, delay: -3 },
  ];

  /** Calm: the cubes near the edges, turning half as fast. */
  protected readonly shown = computed(() => {
    const edges = this.cubes.filter((cube) => cube.x < 16 || cube.x > 76);
    if (this.calm()) return edges.map((cube) => ({ ...cube, spin: cube.spin * 2 }));
    return this.edgesOnly() ? edges : this.cubes;
  });

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    const reduced = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduced || typeof window === 'undefined') return;
    let frame = 0;
    // Tilt the scene a few degrees toward the mouse; at most one update per animation frame.
    const onMove = (event: PointerEvent) => {
      if (event.pointerType !== 'mouse' || frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        const x = event.clientX / window.innerWidth - 0.5;
        const y = event.clientY / window.innerHeight - 0.5;
        host.style.setProperty('--tilt-y', `${(x * 8).toFixed(2)}deg`);
        host.style.setProperty('--tilt-x', `${(-y * 6).toFixed(2)}deg`);
      });
    };
    window.addEventListener('pointermove', onMove, { passive: true });
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('pointermove', onMove);
      if (frame) cancelAnimationFrame(frame);
    });
  }
}
