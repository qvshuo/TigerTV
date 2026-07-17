import Foundation

// 约束 failure 为 TigerTVError（Sendable），使 LoadResult 整体满足 Sendable：
// Swift 6 下 `any Error` 不是 Sendable，会导致 enum 的 Sendable 声明不成立。
enum LoadResult<T: Sendable>: Sendable {
    case success(T)
    case failure(TigerTVError)
}
