package cn.xuyinyin.cdc

/**
 * 项目内 CBOR 序列化标记 trait。
 *
 * 凡是会跨 cluster 节点传递的消息类型（Singleton 命令/响应、Sharding 消息等）
 * 都应继承此 trait。reference.conf 会把它绑定到 `jackson-cbor` 序列化器。
 *
 * 不要使用 `java.io.Serializable` 作为绑定 —— Pekko 出于安全考虑禁止
 * 把"开放接口"绑到 jackson 序列化器（任何 Serializable 都会被吞掉）。
 */
trait CborSerializable
