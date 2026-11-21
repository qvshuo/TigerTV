export const hotLimit = 36

// This endpoint only accepts files from Douban's public poster directory.
export const posterRoute = /^poster\/(img[1-9])\/(p\d{1,20}\.jpg)$/

export function posterKey(url: string) {
  const match = /^https:\/\/(img[1-9])\.doubanio\.com\/view\/photo\/m_ratio_poster\/public\/(p\d{1,20}\.jpg)$/.exec(url)
  return match ? `poster/${match[1]}/${match[2]}` : undefined
}
