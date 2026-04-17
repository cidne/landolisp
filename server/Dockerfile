# syntax=docker/dockerfile:1.6
#
# Landolisp sandbox image (multi-stage).
#
# Stages:
#   1. base        — SBCL + Quicklisp installed under /opt/quicklisp.
#   2. core-builder — loads runner.lisp and saves a purified core image at
#                    /opt/sandbox/landolisp-runner.core. This stage exists
#                    so the runtime image carries a precompiled, minimal
#                    runner that does NOT include the Hunchentoot HTTP
#                    surface.
#   3. runtime     — final image. Copies the runner core out of the
#                    builder stage, installs firejail + tini, lays down
#                    the parent server sources, and runs as the
#                    unprivileged `sandbox` user.
#
# Two cores live in this image on purpose:
#   - landolisp.core (parent): served by Hunchentoot, never CL:EVALs user
#     code. It DOES contain the HTTP server, JSON encoder, etc.
#   - landolisp-runner.core (child): tiny image used by the per-session
#     subprocess. Has Quicklisp + the runner loop and nothing else.
#
# ENTRYPOINT chain: tini -- sbcl --core landolisp.core …  -> start-server
# Children are spawned per-session by subprocess.lisp.

# ---------------------------------------------------------------------------
# Stage 1: base — SBCL + Quicklisp + system libs (firejail for the runtime).
# ---------------------------------------------------------------------------
# Using debian-slim + apt-get sbcl rather than clfoundation/sbcl because the
# latter's tagged versions get pruned aggressively from Docker Hub. Debian
# stable ships sbcl 2.2.x which is sufficient for everything in the curriculum;
# upgrade to a newer SBCL by sourcing from clfoundation once we depend on a
# 2.4-only feature.
FROM debian:bookworm-slim AS base

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        sbcl \
        curl ca-certificates git \
        firejail \
        tini \
        build-essential libssl-dev libffi-dev zlib1g-dev \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    QUICKLISP_HOME=/opt/quicklisp \
    LANDOLISP_HOME=/opt/landolisp-sandbox

# --- create the unprivileged sandbox user ----------------------------------
RUN useradd --system --create-home --home-dir /home/sandbox \
            --shell /usr/sbin/nologin --uid 1000 sandbox \
 && mkdir -p /var/sandbox /opt/landolisp-sandbox /opt/sandbox \
 && chown -R sandbox:sandbox /var/sandbox /opt/landolisp-sandbox /opt/sandbox

# --- install Quicklisp non-interactively into /opt/quicklisp ---------------
RUN curl -fsSL -o /tmp/quicklisp.lisp https://beta.quicklisp.org/quicklisp.lisp \
 && sbcl --non-interactive \
         --load /tmp/quicklisp.lisp \
         --eval "(quicklisp-quickstart:install :path \"${QUICKLISP_HOME}/\")" \
         --eval "(let ((ql-util::*do-not-prompt* t)) (ql:add-to-init-file))" \
 && rm -f /tmp/quicklisp.lisp \
 && chown -R sandbox:sandbox ${QUICKLISP_HOME}

# --- pre-quickload the allow-listed libraries so first-request is fast -----
# Split into required (server deps) and optional (curriculum libs); the helper
# script prints per-library status so CI logs name the exact failing system.
COPY --chown=sandbox:sandbox scripts/preload.lisp /tmp/preload.lisp
USER sandbox
RUN sbcl --dynamic-space-size 4096 --non-interactive \
         --load ${QUICKLISP_HOME}/setup.lisp \
         --load /tmp/preload.lisp

# Add Quicklisp to the sandbox user's init file so non-interactive sbcl
# auto-loads it on container start.
RUN printf '%s\n' '(load "/opt/quicklisp/setup.lisp")' >> /home/sandbox/.sbclrc

USER root

# ---------------------------------------------------------------------------
# Stage 2: core-builder — pre-builds the runner core image.
# ---------------------------------------------------------------------------
FROM base AS core-builder

# Only the runner sources are needed for the core; copying the whole tree
# keeps the build deterministic and trivially picks up runner.lisp.
COPY --chown=sandbox:sandbox src/runner.lisp /tmp/runner.lisp

USER sandbox
# bordeaux-threads is a runtime dep of the runner (defence-in-depth
# with-timeout) and ql is used by :quickload dispatch. Both load once
# here and get baked into the saved core.
RUN sbcl --non-interactive --no-sysinit --no-userinit \
         --load /opt/quicklisp/setup.lisp \
         --eval "(ql:quickload :bordeaux-threads)" \
         --load /tmp/runner.lisp \
         --eval "(sb-ext:save-lisp-and-die \"/opt/sandbox/landolisp-runner.core\" \
                                            :toplevel #'landolisp.runner:main \
                                            :purify t)"

# ---------------------------------------------------------------------------
# Stage 3: runtime — final image.
# ---------------------------------------------------------------------------
FROM base AS runtime

# Pull the saved runner core out of the builder stage.
COPY --from=core-builder /opt/sandbox/landolisp-runner.core /opt/sandbox/landolisp-runner.core
RUN chown sandbox:sandbox /opt/sandbox/landolisp-runner.core

# --- copy project sources --------------------------------------------------
COPY --chown=sandbox:sandbox . /opt/landolisp-sandbox/

# Tell ASDF to discover landolisp-sandbox.asd under /opt/landolisp-sandbox/.
RUN mkdir -p /home/sandbox/.config/common-lisp/source-registry.conf.d \
 && printf '%s\n' '(:tree #p"/opt/landolisp-sandbox/")' \
    > /home/sandbox/.config/common-lisp/source-registry.conf.d/10-landolisp.conf \
 && chown -R sandbox:sandbox /home/sandbox/.config

# Drop the seccomp profile the runner uses if firejail's --seccomp default
# is not strict enough for the deployment. The flag set we pass to
# firejail is conservative enough that this profile is mostly informational
# / for non-firejail deployments using runc seccomp.
RUN mkdir -p /opt/sandbox/seccomp \
 && cp /opt/landolisp-sandbox/seccomp/landolisp-runner.json /opt/sandbox/seccomp/landolisp-runner.json \
 && chown -R sandbox:sandbox /opt/sandbox/seccomp

# Mark the image as a real container so tests that need OS isolation know
# they should actually run.
ENV INSIDE_DOCKER=1 \
    LANDOLISP_RUNNER_CORE=/opt/sandbox/landolisp-runner.core

WORKDIR /var/sandbox
USER sandbox
EXPOSE 8080

# tini is our PID 1: it reaps zombie children spawned by subprocess.lisp
# and forwards SIGINT / SIGTERM cleanly to SBCL. Without this, an SBCL
# child that exits on its own can stick around as a zombie because
# Hunchentoot's worker thread does not waitpid().
STOPSIGNAL SIGINT
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sbcl", "--non-interactive", \
     "--eval", "(ql:quickload :landolisp-sandbox)", \
     "--eval", "(landolisp.sandbox:start-server :port 8080)", \
     "--eval", "(loop (sleep 60))"]
