import shiopaSource from "./shiopa"
import primarySource from "./rei"
import tertiarySource from "../../shade/dev/vidrock"
import type { ScraperPlugin } from "../nano/plugins-loader"

const publicSources = [
  { id: "shiopa", name: "Shiopa", rank: 1, source: shiopaSource },
  { id: "rei", name: "Rei", rank: 2, source: primarySource },
  { id: "yume", name: "Yume", rank: 3, source: tertiarySource },
] as const

export const builtInProviders: ScraperPlugin[] = publicSources.map(({ id, name, rank, source }) => ({
  ...source,
  key: id,
  name,
  rank,
}))

export function mergeProviders(plugins: ScraperPlugin[]): ScraperPlugin[] {
  const providers = new Map<string, ScraperPlugin>()
  for (const provider of [...builtInProviders, ...plugins]) {
    const allowed = publicSources.find((source) => source.id === provider.key)
    if (allowed && !providers.has(allowed.id)) {
      providers.set(allowed.id, { ...provider, key: allowed.id, name: allowed.name })
      continue
    }
    if (!providers.has(provider.key)) providers.set(provider.key, provider)
  }
  return [...providers.values()]
}
