#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Draws the line diagrams used in the documentation.

The diagrams use the same shapes as the picture in the app
(app/src/main/kotlin/com/everysoft/paprelay/ui/FlowDiagram.kt). They carry
labels only; the explanations are in the Markdown next to them, where a
browser can translate them. The canvas is narrow so that the text stays
readable on a phone.

Run from the repository root:

    python3 tools/gen_doc_diagrams.py

It rewrites docs/images/*.svg. Do not edit those files by hand.
"""

from html import escape
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs" / "images"
WIDTH = 480

STYLE = """
  .paper { fill: #ffffff; stroke: #d0d7de; stroke-width: 1; }
  .ink { fill: none; stroke: #1f2328; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round; }
  .thin { fill: none; stroke: #1f2328; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
  .line { fill: none; stroke: #6e7781; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round; }
  .faint { fill: none; stroke: #afb8c1; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
  .dash { stroke-dasharray: 7 7; }
  .solid { fill: #1f2328; stroke: none; }
  .hole { fill: #ffffff; stroke: none; }
  .bad { fill: #cf222e; stroke: none; }
  .badline { fill: none; stroke: #cf222e; stroke-width: 2.4; stroke-linecap: round; }
  .onbad { fill: none; stroke: #ffffff; stroke-width: 2.4; stroke-linecap: round; }
  text { font-family: -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; fill: #1f2328; }
  .t { font-size: 16px; }
  .b { font-size: 16px; font-weight: 600; }
  .s { font-size: 14px; fill: #57606a; }
  .c { font-size: 13px; }
  .x { font-size: 12px; fill: #57606a; }
  .num { font-size: 14px; font-weight: 700; fill: #ffffff; }
  .on { fill: #ffffff; }
  @media (prefers-color-scheme: dark) {
    .paper { fill: #0d1117; stroke: #30363d; }
    .ink, .thin { stroke: #e6edf3; }
    .line { stroke: #8b949e; }
    .faint { stroke: #484f58; }
    .solid { fill: #e6edf3; }
    .hole { fill: #0d1117; }
    .bad { fill: #f85149; }
    .badline { stroke: #f85149; }
    .onbad { stroke: #0d1117; }
    text { fill: #e6edf3; }
    .s, .x { fill: #8b949e; }
    .num, .on { fill: #0d1117; }
  }
"""


def f(value):
    """A number as SVG wants it: short, and never in exponent form."""
    return f"{value:.1f}".rstrip("0").rstrip(".")


class Svg:
    def __init__(self, height, title):
        self.height = height
        self.title = title
        self.parts = []

    def add(self, text):
        self.parts.append(text)

    def save(self, name):
        title = escape(self.title, quote=True)
        body = "\n".join(self.parts)
        text = (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {WIDTH} {self.height}" '
            f'width="{WIDTH}" height="{self.height}" role="img" aria-label="{title}">\n'
            f"<title>{title}</title>\n<style>{STYLE}</style>\n"
            f'<rect class="paper" x="0.5" y="0.5" width="{WIDTH - 1}" height="{self.height - 1}" rx="12"/>\n'
            f"{body}\n</svg>\n"
        )
        OUT.mkdir(parents=True, exist_ok=True)
        # Unix line ends on every system, so that the files do not change from one computer to the next.
        (OUT / name).write_text(text, encoding="utf-8", newline="\n")
        return name

    # ---- text

    def text(self, x, y, words, cls="t", anchor="start"):
        self.add(f'<text class="{cls}" x="{f(x)}" y="{f(y)}" text-anchor="{anchor}">{escape(words, quote=False)}</text>')

    def lines(self, x, y, rows, cls="t", gap=21, anchor="start"):
        for index, row in enumerate(rows):
            self.text(x, y + index * gap, row, cls, anchor)

    # ---- the three shapes of the app's picture

    def machine(self, x, y, u, cls="ink"):
        w = 1.5 * u
        self.add(f'<rect class="{cls}" x="{f(x + 1)}" y="{f(y + 0.20 * u)}" width="{f(w - 2)}" height="{f(0.64 * u)}" rx="{f(0.16 * u)}"/>')
        self.add(f'<rect class="{cls}" x="{f(x + 0.10 * w)}" y="{f(y + 0.46 * u)}" width="{f(0.30 * w)}" height="{f(0.26 * u)}" rx="{f(0.05 * u)}"/>')
        self.add(f'<circle class="{cls}" cx="{f(x + 0.25 * w)}" cy="{f(y + 0.33 * u)}" r="{f(0.065 * u)}"/>')
        seam = x + 0.70 * w
        self.add(f'<path class="{cls}" d="M{f(seam)} {f(y + 0.27 * u)}V{f(y + 0.77 * u)}M{f(seam)} {f(y + 0.60 * u)}H{f(x + w - 1)}M{f(x + 0.14 * w)} {f(y + 0.93 * u)}H{f(x + 0.86 * w)}"/>')
        return w

    def phone(self, x, y, u, cls="ink"):
        w = 0.5 * u
        self.add(f'<rect class="{cls}" x="{f(x + 1)}" y="{f(y + 0.06 * u)}" width="{f(w - 2)}" height="{f(0.88 * u)}" rx="{f(0.09 * u)}"/>')
        self.add(f'<path class="{cls}" d="M{f(x + 0.34 * w)} {f(y + 0.84 * u)}H{f(x + 0.66 * w)}"/>')
        return w

    def heart(self, x, y, u, cls="ink"):
        w = 0.84 * u
        h = u

        def p(a, b):
            return f"{f(x + w * a)} {f(y + h * b)}"

        self.add(
            f'<path class="{cls}" d="M{p(0.50, 0.86)}C{p(-0.13, 0.58)} {p(-0.08, 0.18)} {p(0.30, 0.18)}'
            f"C{p(0.42, 0.18)} {p(0.50, 0.26)} {p(0.50, 0.34)}C{p(0.50, 0.26)} {p(0.58, 0.18)} {p(0.70, 0.18)}"
            f'C{p(1.08, 0.18)} {p(1.13, 0.58)} {p(0.50, 0.86)}Z"/>'
        )
        return w

    # ---- other icons

    def page(self, x, y, w, h, rows=3):
        """A sheet of paper with a folded corner."""
        fold = w * 0.32
        self.add(f'<path class="ink" d="M{f(x)} {f(y)}H{f(x + w - fold)}L{f(x + w)} {f(y + fold)}V{f(y + h)}H{f(x)}Z"/>')
        self.add(f'<path class="thin" d="M{f(x + w - fold)} {f(y)}V{f(y + fold)}H{f(x + w)}"/>')
        for row in range(rows):
            at = y + h * (0.48 + 0.16 * row)
            self.add(f'<path class="thin" d="M{f(x + w * 0.2)} {f(at)}H{f(x + w * 0.8)}"/>')

    def share(self, x, y, size):
        """The share icon: three points joined by two lines."""
        r = size * 0.13
        left = (x + size * 0.2, y + size * 0.5)
        top = (x + size * 0.8, y + size * 0.18)
        bottom = (x + size * 0.8, y + size * 0.82)
        self.add(f'<path class="ink" d="M{f(left[0])} {f(left[1])}L{f(top[0])} {f(top[1])}M{f(left[0])} {f(left[1])}L{f(bottom[0])} {f(bottom[1])}"/>')
        for cx, cy in (left, top, bottom):
            self.add(f'<circle class="hole" cx="{f(cx)}" cy="{f(cy)}" r="{f(r + 2)}"/>')
            self.add(f'<circle class="ink" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')

    # ---- the glyphs of the app's navigation (app/.../ui/Glyphs.kt), on a grid of 24 scaled to [size]

    def moon(self, x, y, size, cls="thin"):
        """A crescent: one circle with a second, offset circle taken out of it."""
        k = size / 24
        (ax, ay, ar), (bx, by, br) = (11, 13, 8), (16, 8.5, 7)
        # Where the two circles cross; the crescent runs along A outside B, then back along B inside A.
        dx, dy = bx - ax, by - ay
        d = (dx * dx + dy * dy) ** 0.5
        a = (ar * ar - br * br + d * d) / (2 * d)
        h = (ar * ar - a * a) ** 0.5
        mx, my = ax + a * dx / d, ay + a * dy / d
        p1 = (mx + h * dy / d, my - h * dx / d)
        p2 = (mx - h * dy / d, my + h * dx / d)

        def q(point):
            return f"{f(x + point[0] * k)} {f(y + point[1] * k)}"

        self.add(
            f'<path class="{cls}" d="M{q(p1)}A{f(ar * k)} {f(ar * k)} 0 1 0 {q(p2)}'
            f'A{f(br * k)} {f(br * k)} 0 0 1 {q(p1)}Z"/>'
        )

    def fork(self, x, y, size, cls="thin"):
        """One source, three destinations."""
        k = size / 24

        def q(px, py):
            return f"{f(x + px * k)} {f(y + py * k)}"

        self.add(f'<circle class="solid" cx="{f(x + 4 * k)}" cy="{f(y + 12 * k)}" r="{f(2.25 * k)}"/>')
        self.add(f'<path class="{cls}" d="M{q(6.5, 12)}L{q(19, 12)}M{q(6.5, 12)}L{q(18.6, 5.6)}M{q(6.5, 12)}L{q(18.6, 18.4)}"/>')
        for cy in (12, 5, 19):
            self.add(f'<circle class="hole" cx="{f(x + 20.5 * k)}" cy="{f(y + cy * k)}" r="{f(3.25 * k)}"/>')
            self.add(f'<circle class="{cls}" cx="{f(x + 20.5 * k)}" cy="{f(y + cy * k)}" r="{f(2.25 * k)}"/>')

    def checked_link(self, x, y, size, cls="thin"):
        """A line with the picture's tick on it."""
        k = size / 24

        def q(px, py):
            return f"{f(x + px * k)} {f(y + py * k)}"

        self.add(f'<path class="{cls}" d="M{q(2, 12)}H{f(x + 22 * k)}"/>')
        self.add(f'<circle class="hole" cx="{f(x + 12 * k)}" cy="{f(y + 12 * k)}" r="{f(7.5 * k)}"/>')
        self.add(f'<circle class="{cls}" cx="{f(x + 12 * k)}" cy="{f(y + 12 * k)}" r="{f(5.5 * k)}"/>')
        self.add(f'<path class="{cls}" d="M{q(9.4, 12.1)}L{q(11.2, 14)}L{q(14.8, 10.2)}"/>')

    def info(self, x, y, size, cls="thin"):
        """An "i" in a ring."""
        k = size / 24
        self.add(f'<circle class="{cls}" cx="{f(x + 12 * k)}" cy="{f(y + 12 * k)}" r="{f(9 * k)}"/>')
        self.add(f'<circle class="solid" cx="{f(x + 12 * k)}" cy="{f(y + 7.6 * k)}" r="{f(1.3 * k)}"/>')
        self.add(f'<path class="{cls}" d="M{f(x + 12 * k)} {f(y + 11 * k)}V{f(y + 16.6 * k)}"/>')

    def chevron(self, x, y, size, cls="line"):
        k = size / 24
        self.add(f'<path class="{cls}" d="M{f(x + 9 * k)} {f(y + 6 * k)}L{f(x + 15 * k)} {f(y + 12 * k)}L{f(x + 9 * k)} {f(y + 18 * k)}"/>')

    def cloud_off(self, x, y, w):
        """A cloud with a line through it: no internet."""
        h = w * 0.62
        self.add(
            f'<path class="line" d="M{f(x + w * 0.24)} {f(y + h)}'
            f"C{f(x - w * 0.02)} {f(y + h)} {f(x - w * 0.02)} {f(y + h * 0.48)} {f(x + w * 0.24)} {f(y + h * 0.50)}"
            f"C{f(x + w * 0.24)} {f(y + h * 0.02)} {f(x + w * 0.70)} {f(y + h * 0.02)} {f(x + w * 0.72)} {f(y + h * 0.40)}"
            f'C{f(x + w * 1.04)} {f(y + h * 0.38)} {f(x + w * 1.04)} {f(y + h)} {f(x + w * 0.76)} {f(y + h)}Z"/>'
        )
        self.add(f'<path class="badline" d="M{f(x + w * 0.08)} {f(y - 2)}L{f(x + w * 0.92)} {f(y + h + 6)}"/>')

    def clock(self, cx, cy, r):
        self.add(f'<circle class="ink" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')
        self.add(f'<path class="ink" d="M{f(cx)} {f(cy - r * 0.55)}V{f(cy)}L{f(cx + r * 0.42)} {f(cy + r * 0.28)}"/>')

    def waves(self, x, cy, size):
        """Radio waves leaving a point at (x, cy), opening to the right."""
        for index in range(3):
            r = size * (0.34 + 0.33 * index)
            dx = r * 0.5
            self.add(f'<path class="line" d="M{f(x + dx)} {f(cy - r * 0.86)}A{f(r)} {f(r)} 0 0 1 {f(x + dx)} {f(cy + r * 0.86)}"/>')

    def button(self, x, y, w, h, words, filled=True):
        shape = "solid" if filled else "thin"
        cls = "c on" if filled else "c"
        self.add(f'<rect class="{shape}" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="{f(h / 2)}"/>')
        self.text(x + w / 2, y + h / 2 + 4.5, words, cls, "middle")

    def bubble(self, x, y, w, h, words, tail_to):
        """A rounded box holding [words], with a short tail towards the thing that shows them."""
        self.add(f'<rect class="thin" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="6"/>')
        self.add(f'<path class="thin" d="M{f(x)} {f(y + h / 2)}L{f(tail_to)} {f(y + h / 2 + 6)}"/>')
        self.text(x + w / 2, y + h / 2 + 5, words, "s", "middle")

    def tap(self, cx, cy, r):
        """A touch: a dot with two rings around it."""
        self.add(f'<circle class="solid" cx="{f(cx)}" cy="{f(cy)}" r="{f(r * 0.3)}"/>')
        self.add(f'<circle class="ink" cx="{f(cx)}" cy="{f(cy)}" r="{f(r * 0.62)}"/>')
        self.add(f'<circle class="faint" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')

    def switch(self, x, y, on):
        w, h = 46, 26
        if on:
            self.add(f'<rect class="solid" x="{f(x)}" y="{f(y)}" width="{w}" height="{h}" rx="13"/>')
            self.add(f'<circle class="hole" cx="{f(x + w - 13)}" cy="{f(y + 13)}" r="8.5"/>')
        else:
            self.add(f'<rect class="line" x="{f(x)}" y="{f(y)}" width="{w}" height="{h}" rx="13"/>')
            self.add(f'<circle class="line" cx="{f(x + 13)}" cy="{f(y + 13)}" r="6.5"/>')

    def box(self, x, y, w, h, title, sub=None, cls="ink"):
        self.add(f'<rect class="{cls}" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="10"/>')
        if sub is None:
            self.text(x + w / 2, y + h / 2 + 5.5, title, "b", "middle")
        else:
            self.text(x + 16, y + 25, title, "b")
            self.text(x + 16, y + 45, sub, "s")

    def arrow(self, x1, y1, x2, y2, cls="ink", reach=6):
        """A straight arrow, across or down."""
        self.add(f'<path class="{cls}" d="M{f(x1)} {f(y1)}L{f(x2)} {f(y2)}"/>')
        if y1 == y2:
            d = 1 if x2 > x1 else -1
            self.add(f'<path class="ink" d="M{f(x2 - d * reach)} {f(y2 - reach)}L{f(x2)} {f(y2)}L{f(x2 - d * reach)} {f(y2 + reach)}"/>')
        else:
            self.add(f'<path class="ink" d="M{f(x2 - reach)} {f(y2 - reach)}L{f(x2)} {f(y2)}L{f(x2 + reach)} {f(y2 - reach)}"/>')

    # ---- marks

    def tick(self, cx, cy, r=11):
        self.add(f'<circle class="hole" cx="{f(cx)}" cy="{f(cy)}" r="{f(r + 3)}"/>')
        self.add(f'<circle class="ink" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')
        k = r * 0.45
        self.add(f'<path class="ink" d="M{f(cx - k)} {f(cy)}L{f(cx - k * 0.2)} {f(cy + k * 0.8)}L{f(cx + k)} {f(cy - k * 0.7)}"/>')

    def cross(self, cx, cy, r=11):
        self.add(f'<circle class="hole" cx="{f(cx)}" cy="{f(cy)}" r="{f(r + 3)}"/>')
        self.add(f'<circle class="bad" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')
        k = r * 0.4
        self.add(f'<path class="onbad" d="M{f(cx - k)} {f(cy - k)}L{f(cx + k)} {f(cy + k)}M{f(cx + k)} {f(cy - k)}L{f(cx - k)} {f(cy + k)}"/>')

    def number(self, cx, cy, n, r=13):
        self.add(f'<circle class="solid" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')
        self.text(cx, cy + 5, str(n), "num", "middle")

    def link(self, x1, x2, y, state):
        """The line between two shapes, in one of the five states the app draws."""
        if state == "none":
            stub = (x2 - x1) * 0.28
            self.add(f'<path class="line" d="M{f(x1)} {f(y)}H{f(x1 + stub)}M{f(x2 - stub)} {f(y)}H{f(x2)}"/>')
        elif state == "ready":
            self.add(f'<path class="line" d="M{f(x1)} {f(y)}H{f(x2)}"/>')
        elif state == "active":
            self.add(f'<path class="ink dash" d="M{f(x1)} {f(y)}H{f(x2 - 3)}"/>')
            self.add(f'<path class="ink" d="M{f(x2 - 7)} {f(y - 7)}L{f(x2)} {f(y)}L{f(x2 - 7)} {f(y + 7)}"/>')
        elif state == "ok":
            self.add(f'<path class="ink" d="M{f(x1)} {f(y)}H{f(x2)}"/>')
            # A smaller tick on a short line, so that some of the line still shows.
            self.tick((x1 + x2) / 2, y, min(11, (x2 - x1) * 0.24))
        elif state == "failed":
            self.add(f'<path class="badline" d="M{f(x1)} {f(y)}H{f(x2)}"/>')
            self.cross((x1 + x2) / 2, y)

    def picture(self, x, y, u, left, right, span, labels=None):
        """The app's picture: machine, phone and heart, and the two lines between them."""
        gap = 10 if u >= 50 else 6
        machine_w = self.machine(x, y, u)
        phone_x = x + machine_w + span
        phone_w = self.phone(phone_x, y, u)
        heart_x = phone_x + phone_w + span
        heart_w = self.heart(heart_x, y, u)
        mid = y + u / 2
        self.link(x + machine_w + gap, phone_x - gap, mid, left)
        self.link(phone_x + phone_w + gap, heart_x - gap, mid, right)
        if labels:
            self.text(x + machine_w / 2, y + u + 18, "Machine", labels, "middle")
            self.text(phone_x + phone_w / 2, y + u + 18, "Phone", labels, "middle")
            self.text(heart_x + heart_w / 2, y + u + 18, "Health Connect", labels, "middle")


def overview():
    s = Svg(232, "The machine sends data to the phone by Bluetooth. The phone passes it on to Health Connect, to a file, or to another app. PAPRelay does not use the internet.")
    u = 56
    mw = s.machine(20, 66, u)
    s.text(20 + mw / 2, 66 + u + 20, "Machine", "t", "middle")
    px = 172
    pw = s.phone(px, 54, 80)
    s.text(px + pw / 2, 54 + 80 + 18, "Phone", "t", "middle")
    s.link(20 + mw + 8, px - 8, 94, "active")
    s.text((20 + mw + px) / 2, 76, "Bluetooth", "x", "middle")
    # One line out of the phone, then three branches.
    trunk = 234
    s.add(f'<path class="ink" d="M{px + pw + 8} 94H{trunk}M{trunk} 38V150M{trunk} 38H252M{trunk} 94H252M{trunk} 150H252"/>')
    for y in (38, 94, 150):
        s.add(f'<path class="ink" d="M247 {y - 6}L253 {y}L247 {y + 6}"/>')
    s.heart(264, 16, 44)
    s.text(314, 43, "Health Connect", "b")
    s.page(266, 73, 32, 42)
    s.text(314, 99, "A file", "b")
    s.share(262, 130, 40)
    s.text(314, 155, "Another app", "b")
    s.cloud_off(22, 188, 30)
    s.text(64, 206, "No internet", "s")
    return s.save("overview.svg")


def picture():
    s = Svg(388, "How to read the picture in the app. Two short lines: not set up yet. A grey line: set up and waiting. A dashed arrow: data is moving. A tick: it worked. A red cross on a line: it did not work. A red cross on the phone: the phone has a problem.")
    s.picture(88, 22, 52, "active", "ready", span=78, labels="s")
    s.add('<path class="faint" d="M24 118H456"/>')
    rows = [
        ("none", "Not set up yet"),
        ("ready", "Set up and waiting"),
        ("active", "Data is moving"),
        ("ok", "It worked"),
        ("failed", "It did not work"),
    ]
    for index, (state, words) in enumerate(rows):
        y = 148 + index * 38
        s.link(56, 176, y, state)
        s.text(204, y + 5.5, words, "t")
    # The same cross on the phone itself.
    y = 148 + 5 * 38 + 10
    pw = s.phone(106, y - 22, 44)
    s.cross(106 + pw, y - 22 + 44 * 0.16, 8)
    s.text(204, y + 5.5, "The phone has a problem", "t")
    return s.save("picture.svg")


def setup():
    s = Svg(444, "First-time setup in five steps: remove the machine from myAir; on the machine choose More, myAir App, Connect; in PAPRelay tap Pair the machine and type the code that the machine shows; wait for the ticks; tap Synchronise now.")
    row = 84
    top = 24
    label_x = 250
    for index in range(5):
        y = top + index * row
        s.number(34, y + 30, index + 1)
        if index:
            s.add(f'<path class="faint" d="M24 {y - 12}H456"/>')

    # Step 1: the machine and myAir, with the "not set up" line between them.
    y = top
    mw = s.machine(70, y - 5, 44)
    pw = s.phone(194, y - 8, 50)
    s.link(70 + mw + 8, 194 - 8, y + 17, "none")
    s.text(194 + pw / 2, y + 58, "myAir", "s", "middle")
    s.lines(label_x, y + 24, ["In myAir:", "remove the machine"])

    # Step 2: the machine waits to be paired.
    y = top + row
    mw = s.machine(88, y + 2, 48)
    s.waves(88 + mw + 6, y + 26, 22)
    s.lines(label_x, y + 24, ["On the machine: More", "› myAir App › Connect"])

    # Step 3: the machine shows the code, and the code is typed into the phone.
    y = top + 2 * row
    mw = s.machine(62, y + 8, 36)
    s.bubble(62 + mw + 12, y + 10, 62, 30, "1 2 3 4", 62 + mw + 3)
    s.phone(62 + mw + 12 + 62 + 12, y - 2, 56)
    s.lines(label_x, y + 24, ["Pair the machine", "Type the code"])

    # Step 4: the device check.
    y = top + 3 * row
    s.picture(62, y + 12, 30, "ok", "ok", span=46)
    s.text(label_x, y + 34, "Wait for the ticks")

    # Step 5.
    y = top + 4 * row
    s.button(62, y + 12, 144, 34, "Synchronise now")
    s.tap(206, y + 44, 14)
    s.text(label_x, y + 34, "Tap the button")
    return s.save("setup.svg")


def home():
    s = Svg(590, "The home screen of PAPRelay, with six numbered parts: 1 the picture, 2 last night, 3 the status, 4 the Synchronise now button, 5 the rows for Nights, Sharing and Device check, 6 the i for About.")
    x, y, w, h = 84, 20, 284, 550
    s.add(f'<rect class="ink" x="{x}" y="{y}" width="{w}" height="{h}" rx="28"/>')
    s.add(f'<path class="ink" d="M{x + 112} {y + h - 16}H{x + w - 112}"/>')
    left = x + 24
    right = x + w - 24
    s.text(left, y + 48, "PAPRelay", "b")
    s.info(right - 22, y + 31, 22)
    s.picture(left, y + 72, 40, "ok", "ok", span=44, labels="x")
    s.text(left, y + 168, "AirSense 11 · serial ending 1234", "c")
    s.text(left, y + 208, "Night of Sat, 14 Nov 2026", "x")
    s.text(left, y + 232, "7 h 12 min · AHI 1.4", "b")
    s.text(left, y + 254, "Leak 95%: 9.6 L/min", "x")
    s.text(left, y + 271, "Pressure 95%: 10.2 cmH₂O", "x")
    s.text(left, y + 312, "Up to date.", "c")
    s.text(left, y + 330, "Last synchronised 5 minutes ago.", "x")
    s.button(left, y + 348, 150, 36, "Synchronise now")
    # The rows that lead to the other pages: glyph, name, chevron, with a faint line above each.
    glyphs = (s.moon, s.fork, s.checked_link)
    for index, (draw, label) in enumerate(zip(glyphs, ("Nights", "Sharing", "Device check"))):
        top = y + 404 + index * 42
        s.add(f'<path class="faint" d="M{left} {top}H{right}"/>')
        mid = top + 21
        draw(left, mid - 10, 20)
        s.text(left + 34, mid + 4.5, label, "c")
        s.chevron(right - 20, mid - 10, 20)
    for index, at in enumerate((y + 92, y + 228, y + 318, y + 366, y + 467, y + 42)):
        s.add(f'<path class="faint" d="M{x + w + 8} {at}H{x + w + 28}"/>')
        s.number(x + w + 46, at, index + 1)
    return s.save("home.svg")


def when():
    s = Svg(178, "PAPRelay synchronises when you tap the button, when the phone is near the machine, and about every hour.")
    centres = [80, 240, 400]
    s.button(centres[0] - 68, 34, 136, 32, "Synchronise now")
    s.tap(centres[0] + 50, 76, 14)
    mw = s.machine(centres[1] - 60, 30, 46)
    s.waves(centres[1] - 60 + mw + 6, 53, 22)
    s.phone(centres[1] + 34, 24, 58)
    s.clock(centres[2], 54, 26)
    captions = [
        ["You tap", "the button"],
        ["The phone is", "near the machine"],
        ["About", "every hour"],
    ]
    for cx, rows in zip(centres, captions):
        s.lines(cx, 124, rows, "t", 21, "middle")
    s.add('<path class="faint" d="M160 26V152M320 26V152"/>')
    return s.save("when.svg")


def night():
    s = Svg(200, "One night runs from midday (12:00) to midday the next day. The night's figures arrive on the phone after the second midday.")
    x1, x2, y = 40, 322, 96
    s.add(f'<path class="ink" d="M{x1} {y}H{x2}"/>')
    middle = (x1 + x2) / 2
    for x, label, sub in ((x1, "12:00", "midday"), (middle, "00:00", "midnight"), (x2, "12:00", "midday")):
        # The midnight mark stays below the line, clear of the therapy bar.
        start = y if x == middle else y - 9
        s.add(f'<path class="ink" d="M{f(x)} {start}V{y + 9}"/>')
        s.text(x, y + 32, label, "b", "middle")
        s.text(x, y + 50, sub, "s", "middle")
    a, b = x1 + (x2 - x1) * 0.42, x1 + (x2 - x1) * 0.78
    s.add(f'<rect class="solid" x="{f(a)}" y="{y - 7}" width="{f(b - a)}" height="14" rx="7"/>')
    s.text((a + b) / 2, y - 18, "therapy", "s", "middle")
    s.add(f'<path class="thin" d="M{x1} {y - 44}V{y - 54}H{x2}V{y - 44}"/>')
    s.text(middle, y - 62, "one night", "t", "middle")
    s.link(x2 + 14, x2 + 46, y, "active")
    pw = s.phone(x2 + 58, y - 42, 84)
    s.tick(x2 + 58 + pw, y - 32, 10)
    s.text(x2 + 58 + pw / 2, y + 68, "after 12:00", "b", "middle")
    return s.save("night.svg")


def sharing():
    s = Svg(346, "The Sharing page: a switch for nightly figures to health records, shown on; a switch for respiratory rate to Health Connect, shown off; Table of nights with the button Choose where to keep it; and the button Share the recent nights as text.")
    row = 80
    top = 22
    for index in range(1, 4):
        s.add(f'<path class="faint" d="M24 {top + index * row - 10}H456"/>')
    y = top
    s.switch(24, y + 16, True)
    s.heart(86, y + 7, 44)
    s.lines(142, y + 24, ["Nightly figures", "to health records"], "b", 21)
    y = top + row
    s.switch(24, y + 16, False)
    s.heart(86, y + 7, 44)
    s.lines(142, y + 24, ["Respiratory rate", "to Health Connect"], "b", 21)
    y = top + 2 * row
    s.page(88, y + 8, 32, 42)
    s.text(142, y + 20, "Table of nights", "b")
    s.button(142, y + 30, 204, 30, "Choose where to keep it", filled=False)
    y = top + 3 * row
    s.share(84, y + 10, 40)
    s.button(142, y + 15, 268, 30, "Share the recent nights as text", filled=False)
    return s.save("sharing.svg")


def modules():
    s = Svg(288, "The three modules. app, the Android app, uses as11 and core. as11, the AirSense 11 protocol, uses core. core holds the model and the rules.")
    s.box(24, 20, 432, 60, "app", "The Android app")
    s.box(24, 114, 270, 60, "as11", "The AirSense 11 protocol")
    s.box(24, 208, 432, 60, "core", "Model and rules")
    s.arrow(160, 83, 160, 110)
    s.arrow(160, 177, 160, 204)
    s.arrow(376, 83, 376, 204)
    return s.save("modules.svg")


def flow():
    s = Svg(296, "How the data flows: from the machine over Bluetooth to the link, the protocol, the decoders and the plans, then to the sync engine, which keeps its state on the phone and writes to Health Connect, to a file and to the screen.")
    mw = s.machine(20, 28, 36)
    s.text(20 + mw / 2, 84, "Machine", "s", "middle")
    s.link(20 + mw + 6, 98, 46, "active")
    stages = [("Link", 102, 52), ("Protocol", 166, 96), ("Decoders", 274, 102), ("Plans", 388, 68)]
    for index, (title, x, w) in enumerate(stages):
        s.box(x, 26, w, 40, title)
        if index:
            s.arrow(x - 12, 46, x - 3, 46, reach=4)
    s.arrow(422, 69, 422, 106)
    s.box(20, 110, 140, 48, "Stored state", cls="line")
    s.add('<path class="line" d="M162 134H178"/>')
    s.box(180, 110, 276, 48, "Sync engine")
    targets = [(214, "heart", "Health Connect"), (318, "page", "A file"), (410, "phone", "The screen")]
    for cx, icon, title in targets:
        s.arrow(cx, 161, cx, 190)
        if icon == "heart":
            s.heart(cx - 18.5, 198, 44)
        elif icon == "page":
            s.page(cx - 16, 200, 32, 42)
        else:
            s.phone(cx - 12, 196, 48)
        s.text(cx, 268, title, "t", "middle")
    return s.save("flow.svg")


def main():
    written = [draw() for draw in (overview, picture, setup, home, when, night, sharing, modules, flow)]
    stale = sorted(path.name for path in OUT.glob("*.svg") if path.name not in written)
    print(f"wrote {len(written)} diagrams to {OUT}")
    if stale:
        print("not written by this script (remove them by hand):", ", ".join(stale))


if __name__ == "__main__":
    main()
