init:
	git config core.hooksPath .githooks
	chmod +x .githooks/pre-commit .githooks/commit-msg