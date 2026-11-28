export type SourceRecommendation = 'ad_filter_verified' | 'no_gambling_in_samples'

export function sourceRecommendation(value: unknown): SourceRecommendation | undefined {
  return value === 'ad_filter_verified' || value === 'no_gambling_in_samples' ? value : undefined
}

export const isCatalogSource = (name: string) => name.includes('🎬') && !name.includes('🔞')
