# syntax=docker/dockerfile:1.6
#
# Landolisp sandbox image.
#
# Strategy:
#   1. Start from clfoundation/sbcl which ships SBCL + ASDF, falling back to
#      debian:bookworm-slim + `apt-get install sbcl` if the clfoundation tag
#      is not available in your mirror.
#   2. Install Quicklisp non-interactively under /opt/quicklisp.
#   3. Pre-quickload the library systems we expect users to touch so the
#      first request does not pay the download/compile tax.
#   4. Copy the server sources to /opt/landolisp-sandbox/ and register that
#      tree with ASDF's source-registry.
#   5. Run everything as an unprivileged sandbox user with WORKDIR /var/sandbox.
#
# ENTRYPOINT chain: `(ql:quickload :landolisp-sandbox)` -> `(start-server)`
# -> `(loop (sleep 60))` so PID 1 stays alive while Hunchentoot's worker
# threads handle traffic.

FROM clfoundation/sbcl:2.4.0

# --- fallback base (uncomment if you switched FROM to debian:bookworm-slim) -
# RUN apt-get update \
#  && apt-get install -y --no-install-recommends sbcl curl ca-certificates git \
#  && rm -rf /var/lib/apt/lists/*

# clfoundation/sbcl is debian-based and normally includes curl + CAs; be
# defensive in case a future tag drops them.
RUN (command -v curl >/dev/null 2>&1) || \
    (apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
     && rm -rf /var/lib/apt/lists/*)

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    QUICKLISP_HOME=/opt/quicklisp \
    LANDOLISP_HOME=/opt/landolisp-sandbox

# --- create the unprivileged sandbox user ----------------------------------
RUN useradd --system --create-home --home-dir /home/sandbox \
            --shell /usr/sbin/nologin --uid 1000 sandbox \
 && mkdir -p /var/sandbox /opt/landolisp-sandbox \
 && chown -R sandbox:sandbox /var/sandbox /opt/landolisp-sandbox

# --- install Quicklisp non-interactively into /opt/quicklisp ---------------
RUN curl -fsSL -o /tmp/quicklisp.lisp https://beta.quicklisp.org/quicklisp.lisp \
 && sbcl --non-interactive \
         --load /tmp/quicklisp.lisp \
         --eval "(quicklisp-quickstart:install :path \"${QUICKLISP_HOME}/\")" \
         --eval "(let ((ql-util::*do-not-prompt* t)) (ql:add-to-init-file))" \
 && rm -f /tmp/quicklisp.lisp \
 && chown -R sandbox:sandbox ${QUICKLISP_HOME}

# --- pre-quickload the allow-listed libraries so first-request is fast -----
# Listed in dependency-friendly order; any failure here fails the build.
USER sandbox
RUN sbcl --non-interactive \
         --load ${QUICKLISP_HOME}/setup.lisp \
         --eval "(ql:quickload '(:alexandria :serapeum :cl-ppcre :cl-json \
                                 :bordeaux-threads :usocket :local-time \
                                 :hunchentoot :trivia :fset :str \
                                 :flexi-streams :drakma :fiveam :ironclad \
                                 :cffi :iterate :cl-who :parenscript \
                                 :cl-csv))"

# Add Quicklisp to the sandbox user's init file so non-interactive sbcl
# auto-loads it on container start.
RUN printf '%s\n' '(load "/opt/quicklisp/setup.lisp")' >> /home/sandbox/.sbclrc

USER root

# --- copy project sources --------------------------------------------------
COPY --chown=sandbox:sandbox . /opt/landolisp-sandbox/

# Tell ASDF to discover landolisp-sandbox.asd under /opt/landolisp-sandbox/.
RUN mkdir -p /home/sandbox/.config/common-lisp/source-registry.conf.d \
 && printf '%s\n' '(:tree #p"/opt/landolisp-sandbox/")' \
    > /home/sandbox/.config/common-lisp/source-registry.conf.d/10-landolisp.conf \
 && chown -R sandbox:sandbox /home/sandbox/.config

WORKDIR /var/sandbox
USER sandbox
EXPOSE 8080

# Three --eval forms: load the system, start the acceptor, then block PID 1
# with a sleep loop so the container does not exit while the (separate
# Hunchentoot worker thread) handles requests.
ENTRYPOINT ["sbcl", "--non-interactive", \
            "--eval", "(ql:quickload :landolisp-sandbox)", \
            "--eval", "(landolisp.sandbox:start-server :port 8080)", \
            "--eval", "(loop (sleep 60))"]
