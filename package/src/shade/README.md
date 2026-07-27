# Adding streaming sources

Sources ship as encrypted `.rink` files only. Plain `.ts` scrapers stay in `src/shade/dev` for maintainers and are never published to npm.

### GitHub vs npm

| Channel | What is public |
|---------|----------------|
| GitHub | `xpass` only (`xpass.ts` + `xpass.rink`) |
| npm | Full encrypted catalog (all `.rink` files) |

Secret dev sources and catalog rinks stay local or on npm. They are gitignored and never pushed to GitHub.

### Install globally (run from anywhere)

```bash
cd shiopa-nano
pnpm link --global
```

### Clone a new site

```bash
cd ~/Desktop
shiopa clone
cd shiopa-nano
shiopa config
shiopa add xpass
pnpm dev
```

Install from npm for the full source catalog:

```bash
pnpm add shiopa-nano
shiopa add --all
```

### CLI commands

```bash
shiopa clone [folder]
shiopa update
shiopa version
shiopa config
shiopa config --open
shiopa add xpass
shiopa add --all
shiopa list
```

### Build catalog (maintainers only)

```bash
pnpm compile-rinks
pnpm compile-rinks:public
```

`compile-rinks` builds every local dev source into `src/shade/catalog/*.rink` before npm publish.

`compile-rinks:public` rebuilds only `xpass.rink` for GitHub.

### npm publish

Only `.rink` catalog files are published. Dev `.ts` files and user `private/` installs are excluded via `.npmignore`.
