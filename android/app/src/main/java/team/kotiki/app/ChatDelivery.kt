package team.kotiki.app

/** Each observer holds its own handoff until its server snapshot contains the ID. */
internal fun mergeChatOutgoing(remote:List<ChatMessage>,local:List<ChatMessage>,handoff:MutableMap<String,ChatMessage>):List<ChatMessage> {
    local.forEach{handoff[it.id]=it}
    remote.forEach{handoff.remove(it.id)}
    return (remote+handoff.values).sortedWith(compareBy<ChatMessage>{it.time}.thenBy{it.id})
}
