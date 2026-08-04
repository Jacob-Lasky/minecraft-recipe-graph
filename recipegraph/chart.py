"""Factorio-style production view: stock levels over time, net rates, network power.

Canvas rather than hand-authored SVG paths, because the series are dense and the
point count varies. Colours are read from the page's CSS custom properties at draw
time so the chart follows the viewer's light/dark theme instead of hardcoding either.
"""

import json

from .render import CSS, _esc, _standalone

CHART_CSS = """
.charts{display:grid;gap:20px;grid-template-columns:1fr}
.chartcard{background:var(--card);border:1px solid var(--line);border-radius:11px;
padding:15px 17px}
.chartcard h2{font:600 10.5px/1 var(--mono);letter-spacing:.11em;text-transform:uppercase;
color:var(--dim);margin:0 0 4px;display:flex;justify-content:space-between;gap:10px}
.chartcard .hint{font-size:12px;color:var(--dim);margin-bottom:12px}
canvas{display:block;width:100%;height:auto}
.legend{display:flex;flex-wrap:wrap;gap:4px 14px;margin-top:12px;font-size:12.5px}
.legend button{display:flex;align-items:center;gap:6px;border:0;background:none;
padding:2px 4px;border-radius:6px;cursor:pointer;color:var(--fg);font:12.5px var(--sans)}
.legend button:hover{background:var(--accent-soft)}
.legend button[aria-pressed=false]{opacity:.38}
.legend .sw{width:11px;height:11px;border-radius:3px;flex:0 0 auto}
.legend .val{font-family:var(--mono);font-variant-numeric:tabular-nums;color:var(--dim)}
.up{color:var(--ok)}.down{color:var(--need)}
.rate{font-family:var(--mono);font-variant-numeric:tabular-nums;white-space:nowrap}
.readout{font:12.5px var(--mono);color:var(--dim);min-height:1.5em;margin-top:8px}
"""

CHART_JS = r"""
(function(){
  var DATA = window.__RECIPE_CHART__;

  function css(name, fallback){
    var v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return v || fallback;
  }

  // Distinct hues spread around the wheel; saturation/lightness tuned per theme so
  // the same series colour stays legible on both grounds.
  function palette(n, dark){
    var out = [];
    for (var i = 0; i < n; i++){
      var h = (i * 360 / Math.max(n,1) + 18) % 360;
      out.push('hsl(' + h.toFixed(0) + ',' + (dark?62:58) + '%,' + (dark?66:42) + '%)');
    }
    return out;
  }

  function fmt(v){
    var a = Math.abs(v);
    if (a >= 1e9) return (v/1e9).toFixed(1)+'B';
    if (a >= 1e6) return (v/1e6).toFixed(1)+'M';
    if (a >= 1e3) return (v/1e3).toFixed(1)+'k';
    if (a >= 10) return v.toFixed(0);
    return (Math.round(v*100)/100).toString();
  }

  function clockLabel(ts){
    var d = new Date(ts*1000);
    return ('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);
  }

  function Chart(canvasId, legendId, readoutId, series, opts){
    var cv = document.getElementById(canvasId);
    if (!cv || !series.length) return null;
    var legend = document.getElementById(legendId);
    var readout = document.getElementById(readoutId);
    var on = series.map(function(){ return true; });
    var hoverX = null;

    function draw(){
      var dark = matchMedia('(prefers-color-scheme: dark)').matches;
      var attr = document.documentElement.getAttribute('data-theme');
      if (attr === 'dark') dark = true; else if (attr === 'light') dark = false;
      var colors = palette(series.length, dark);

      var dpr = window.devicePixelRatio || 1;
      var w = cv.clientWidth || 800, h = opts.height || 260;
      cv.width = w * dpr; cv.height = h * dpr;
      var g = cv.getContext('2d');
      g.setTransform(dpr,0,0,dpr,0,0);
      g.clearRect(0,0,w,h);

      var padL = 54, padR = 10, padT = 8, padB = 24;
      var plotW = w - padL - padR, plotH = h - padT - padB;

      var t0 = DATA.since, t1 = DATA.until;
      var lo = 0, hi = 0, any = false;
      series.forEach(function(s, i){
        if (!on[i]) return;
        s.points.forEach(function(p){
          if (!any){ lo = hi = p[1]; any = true; }
          if (p[1] < lo) lo = p[1];
          if (p[1] > hi) hi = p[1];
        });
      });
      if (!any){ lo = 0; hi = 1; }
      if (opts.zeroBase && lo > 0) lo = 0;
      if (hi === lo) hi = lo + 1;
      var span = hi - lo;
      lo -= span * 0.06; hi += span * 0.06;

      function X(ts){ return padL + (ts - t0) / Math.max(t1 - t0, 1) * plotW; }
      function Y(v){ return padT + plotH - (v - lo) / (hi - lo) * plotH; }

      // grid + y labels
      g.strokeStyle = css('--line','#ddd'); g.fillStyle = css('--dim','#888');
      g.lineWidth = 1; g.font = '11px ui-monospace, monospace';
      g.textAlign = 'right'; g.textBaseline = 'middle';
      for (var i = 0; i <= 4; i++){
        var v = lo + (hi - lo) * i / 4, y = Math.round(Y(v)) + 0.5;
        g.globalAlpha = 0.55; g.beginPath();
        g.moveTo(padL, y); g.lineTo(w - padR, y); g.stroke();
        g.globalAlpha = 1; g.fillText(fmt(v), padL - 8, y);
      }
      // x labels
      g.textAlign = 'center'; g.textBaseline = 'top';
      for (var k = 0; k <= 4; k++){
        var ts = t0 + (t1 - t0) * k / 4;
        g.fillText(clockLabel(ts), X(ts), padT + plotH + 6);
      }

      series.forEach(function(s, i){
        if (!on[i] || !s.points.length) return;
        g.strokeStyle = colors[i]; g.lineWidth = 1.8;
        g.lineJoin = 'round'; g.beginPath();
        s.points.forEach(function(p, j){
          var x = X(p[0]), y = Y(p[1]);
          if (j === 0) g.moveTo(x,y);
          // levels are step functions: a value holds until the next recorded change
          else { g.lineTo(x, Y(s.points[j-1][1])); g.lineTo(x, y); }
        });
        var last = s.points[s.points.length-1];
        g.lineTo(X(t1), Y(last[1]));
        g.stroke();
        g.fillStyle = colors[i];
        g.beginPath(); g.arc(X(t1), Y(last[1]), 2.6, 0, 6.2832); g.fill();
      });

      if (hoverX !== null && hoverX >= padL && hoverX <= w - padR){
        g.strokeStyle = css('--accent','#2c8a8f'); g.globalAlpha = .6;
        g.beginPath(); g.moveTo(hoverX+0.5, padT); g.lineTo(hoverX+0.5, padT+plotH);
        g.stroke(); g.globalAlpha = 1;
        var ts = t0 + (hoverX - padL) / plotW * (t1 - t0);
        var parts = [clockLabel(ts)];
        series.forEach(function(s,i){
          if (!on[i] || !s.points.length) return;
          var v = s.points[0][1];
          for (var j=0;j<s.points.length;j++){ if (s.points[j][0] <= ts) v = s.points[j][1]; }
          parts.push(s.label + ' ' + fmt(v));
        });
        if (readout) readout.textContent = parts.join('   ');
      }

      // legend swatches follow the same palette
      if (legend && legend.dataset.built === '1'){
        Array.prototype.forEach.call(legend.querySelectorAll('.sw'), function(el, i){
          el.style.background = colors[i];
        });
      }
    }

    if (legend && legend.dataset.built !== '1'){
      series.forEach(function(s, i){
        var b = document.createElement('button');
        b.type = 'button'; b.setAttribute('aria-pressed','true');
        b.innerHTML = '<span class="sw"></span><span>' + s.label +
          '</span><span class="val">' + s.rateLabel + '</span>';
        b.onclick = function(){
          on[i] = !on[i];
          b.setAttribute('aria-pressed', on[i] ? 'true' : 'false');
          draw();
        };
        legend.appendChild(b);
      });
      legend.dataset.built = '1';
    }

    cv.addEventListener('mousemove', function(e){
      hoverX = e.clientX - cv.getBoundingClientRect().left; draw();
    });
    cv.addEventListener('mouseleave', function(){
      hoverX = null; if (readout) readout.textContent = ''; draw();
    });
    addEventListener('resize', draw);
    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', draw);
    new MutationObserver(draw).observe(document.documentElement,
      {attributes:true, attributeFilter:['data-theme']});
    draw();
    return draw;
  }

  Chart('items','itemlegend','itemreadout', DATA.items, {height:300, zeroBase:true});
  if (DATA.power && DATA.power.length){
    Chart('power','powerlegend','powerreadout', DATA.powerSeries, {height:200});
  }
})();
"""


def _rate_label(per_min):
    sign = "+" if per_min >= 0 else ""
    if abs(per_min) >= 1000:
        return "%s%.1fk/min" % (sign, per_min / 1000.0)
    if abs(per_min) >= 1:
        return "%s%.0f/min" % (sign, per_min)
    return "%s%.2f/min" % (sign, per_min)


def render_chart_html(payload, standalone=False):
    """payload: {since, until, window_label, movers:[...], series:{key:[(ts,qty)]},
    power:[...], source, storage:{...}}

    `standalone=True` for a file a browser opens directly, exactly as in `render.py`, whose
    module docstring carries the argument for why that is two meta tags and not a document.
    THE ONLY CALLER TODAY IS `cli.cmd_chart`, which writes a file, so this page has never
    been served -- and it had #138 too, unreported, because nothing measures it. The flag is
    still explicit rather than always-on: the day a `/chart` route appears it needs the
    fragment, and a renderer that decides for itself is the failure `iconset.resolver`
    records.
    """
    movers = payload["movers"]
    series = []
    for m in movers:
        pts = payload["series"].get(m["key"]) or []
        series.append({
            "label": m["label"],
            "points": [[int(t), int(v)] for t, v in pts],
            "rateLabel": _rate_label(m["per_min"]),
        })

    power = payload.get("power") or []
    power_series = []
    if power:
        power_series = [
            {"label": "Stored AE", "rateLabel": "",
             "points": [[p["ts"], p["stored"] or 0] for p in power]},
            {"label": "Avg use", "rateLabel": "",
             "points": [[p["ts"], p["avg_use"] or 0] for p in power]},
            {"label": "Avg injected", "rateLabel": "",
             "points": [[p["ts"], p["avg_inject"] or 0] for p in power]},
        ]

    data = {
        "since": payload["since"], "until": payload["until"],
        "items": series, "power": power, "powerSeries": power_series,
    }

    rows = []
    for m in movers:
        cls = "up" if m["delta"] > 0 else "down"
        rows.append(
            '<tr><td>%s</td><td class="n">%s</td><td class="n">%s</td>'
            '<td class="n %s">%s%s</td><td class="n %s rate">%s</td></tr>'
            % (_esc(m["label"]), "{:,}".format(m["first"]), "{:,}".format(m["last"]),
               cls, "+" if m["delta"] > 0 else "", "{:,}".format(m["delta"]),
               cls, _rate_label(m["per_min"])))

    st = payload.get("storage") or {}
    gaining = sum(1 for m in movers if m["delta"] > 0)
    draining = sum(1 for m in movers if m["delta"] < 0)

    power_block = ""
    if power:
        latest = power[-1]
        power_block = """
  <div class="chartcard">
    <h2><span>Network power</span><span>%s AE stored</span></h2>
    <div class="hint">Live from the ME network. Average use and injection are AE2's own
    rolling figures, not derived from levels.</div>
    <canvas id="power" height="200"></canvas>
    <div class="legend" id="powerlegend"></div>
    <div class="readout" id="powerreadout"></div>
  </div>""" % ("{:,.0f}".format(latest.get("stored") or 0))

    return _standalone("""<style>%s%s</style>
<div class="wrap">
  <div class="eyebrow">Production monitor</div>
  <h1>AE2 network activity<span class="x">%s</span></h1>
  <div class="id">%s &middot; source: %s</div>

  <div class="stats">
    <div class="stat"><div class="k">Items moving</div><div class="v">%d</div></div>
    <div class="stat ok"><div class="k">Accumulating</div><div class="v">%d</div></div>
    <div class="stat need"><div class="k">Draining</div><div class="v">%d</div></div>
    <div class="stat"><div class="k">Rows stored</div><div class="v">%s</div></div>
  </div>

  <div class="warnbar">These are <b>net</b> rates: AE2 reports stock levels, not machine
  throughput, so an item produced and consumed at the same speed shows as flat. Unlike
  Factorio, production and consumption cannot be separated from this data.</div>

  <div class="charts">
    <div class="chartcard">
      <h2><span>Stock level</span><span>top %d movers</span></h2>
      <div class="hint">Step lines: a level holds until the next recorded change.
      Click a legend entry to hide it.</div>
      <canvas id="items" height="300"></canvas>
      <div class="legend" id="itemlegend"></div>
      <div class="readout" id="itemreadout"></div>
    </div>%s
    <div class="chartcard">
      <h2><span>Net change</span><span>%s</span></h2>
      <div style="overflow-x:auto"><table>
        <tr><td class="k">Item</td><td class="n">Start</td><td class="n">Now</td>
            <td class="n">Change</td><td class="n">Net rate</td></tr>
        %s
      </table></div>
    </div>
  </div>
  <div class="foot">Sampled into tiered buckets (1&nbsp;min kept 2&nbsp;h, 10&nbsp;min kept
  2&nbsp;d, 1&nbsp;h kept 60&nbsp;d) and written only when a quantity actually changes, so
  the database stays small on a network this size.</div>
</div>
<script>window.__RECIPE_CHART__ = %s;</script>
<script>%s</script>""" % (
        CSS, CHART_CSS,
        _esc(payload.get("window_label", "")),
        _esc(payload.get("range_label", "")),
        _esc(payload.get("source", "save")),
        len(movers), gaining, draining,
        "{:,}".format(st.get("level_rows", 0)),
        len(movers),
        power_block,
        _esc(payload.get("window_label", "")),
        "".join(rows) or '<tr><td class="empty">no changes recorded yet</td></tr>',
        json.dumps(data, separators=(",", ":")),
        CHART_JS,
    ), standalone)
