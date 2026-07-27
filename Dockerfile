# recipegraph web UI, containerised so it can run on the home server rather than on a
# laptop that gets closed.
#
# NO PIP INSTALL, DELIBERATELY. The tool is Python 3 stdlib only, which is also why CI has
# no install step and why `serve` can run inside a locked-down container. If a dependency
# ever arrives, the CI comment in .github/workflows/test.yml has to change with it.
FROM python:3.14-slim-trixie

# 3.14 is the current stable line (3.15 is still beta). The CI matrix runs 3.8, the floor
# the code claims to support, and this exact line, so the version that ships is a version
# that is tested. Bump both together or the claim rots.

WORKDIR /app

# Unbuffered, or `docker logs` shows nothing while the server spends its first 40 to 90
# seconds loading the graph, and the startup line never appears at all. A service whose
# logs are empty during the one phase you would want to watch is a service you cannot
# tell apart from a hung one.
ENV PYTHONUNBUFFERED=1

# Source only. `data/` is a bind mount at runtime and `mod/` is a Gradle project that has
# no business in a Python image; see .dockerignore.
COPY recipegraph/ ./recipegraph/

# Byte-compile as root, while /app is still writable. The container then runs as 99:100
# against a root-owned /app, so without this every start silently fails to write .pyc and
# re-parses the whole package. Doubles as the same import check CI runs.
RUN python -m compileall -q recipegraph

# UnRAID's nobody:users. The array shares this writes into (`/data`) expect this ownership,
# and a root-owned file dropped in one wedges every other service that touches the share.
USER 99:100

# Everything mutable lives under /data, bind-mounted at runtime: the graph, the AE2 stock
# snapshot, the machine and free-source overrides, the metrics DB and the cost cache.
#
# NO `VOLUME ["/data"]` on purpose. It would silently mount an empty anonymous volume when
# someone forgets `-v`, and the container would come up serving nothing. Without it the
# same mistake exits immediately with "no graph at /data/graph.json", which is the failure
# you want.
EXPOSE 8765

# 8765 is a USER-FACING contract, not a preference: `/recipedump` tells the player in chat
# to open http://localhost:8765, and the README and the skill repeat it. Changing it means
# changing DumpCommand.java too. See recipegraph/defaults.py.
#
# `--host 0.0.0.0` is required INSIDE a container: binding the loopback default would make
# the server unreachable through the published port. That does NOT loosen the rule the
# default encodes ("the graph exposes a live base's contents and there is no auth, so
# binding wider has to be a deliberate act"). The boundary just moves outward to the port
# publish: keep it on 127.0.0.1 or a trusted LAN, never a public interface.
CMD ["python", "-m", "recipegraph.cli", \
     "--graph", "/data/graph.json", "serve", \
     "--host", "0.0.0.0", "--port", "8765", \
     "--have", "/data/ae2_have.json", \
     "--machines", "/data/machines.json", \
     "--sources", "/data/sources.json"]

# `serve` reads and indexes a 115 MB graph before it answers anything, which takes 40 to 90
# seconds on the real pack. start-period covers that, or the container is killed and
# restarted forever while it is doing exactly what it should. urllib rather than curl
# because the slim image has no curl and adding one for a healthcheck is not worth a layer.
HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=3 \
  CMD ["python", "-c", \
       "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8765/healthz', timeout=5).status == 200 else 1)"]
