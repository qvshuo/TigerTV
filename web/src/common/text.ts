export function foldWidth(text: string) {
  return text.replace(/[\u3000\uff01-\uff5e]/g, char => char === '\u3000' ? ' ' : String.fromCharCode(char.charCodeAt(0) - 0xfee0))
}
