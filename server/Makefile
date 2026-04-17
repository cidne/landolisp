# Convenience wrappers around the sandbox image. None of this is required
# by CI; it's purely for the local dev loop.

IMAGE ?= landolisp-sandbox
TAG   ?= dev
PORT  ?= 8080

.PHONY: build run shell clean test

build:
	docker build -t $(IMAGE):$(TAG) .

run:
	docker run --rm -it -p $(PORT):8080 --name $(IMAGE) $(IMAGE):$(TAG)

shell:
	docker run --rm -it --entrypoint /bin/bash $(IMAGE):$(TAG)

clean:
	-docker rmi $(IMAGE):$(TAG)
	-rm -f landolisp.core
	-rm -rf tmp/

# The test target runs FiveAM inside the image. It needs a build first.
test: build
	docker run --rm $(IMAGE):$(TAG) \
	  sbcl --non-interactive \
	       --eval "(load \"/root/quicklisp/setup.lisp\")" \
	       --eval "(asdf:load-asd \"/app/tests/landolisp-sandbox-tests.asd\")" \
	       --eval "(ql:quickload :landolisp-sandbox-tests)" \
	       --eval "(uiop:quit (if (fiveam:run-all-tests) 0 1))"
