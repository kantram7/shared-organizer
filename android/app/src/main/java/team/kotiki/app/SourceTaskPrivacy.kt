package team.kotiki.app

/** Only the generated final source suffix controls privacy; body links are untrusted text. */
internal fun taskSourcePrivate(
    text:String,
    notePrivate:(String)->Boolean?,
    entryVisibility:(String)->Visibility?
):Boolean {
    val source=Regex("""\n\nИсточник: kotiki://(note|entry)/([A-Za-z0-9_-]{1,128})\z""").find(text)
    if(source!=null)return when(source.groupValues[1]) {
        "note"->notePrivate(source.groupValues[2]) ?: true
        else->entryVisibility(source.groupValues[2])!=Visibility.SHARED
    }
    // Plans and app chats are shared. Their generated suffixes have separate strict shapes.
    if(Regex("""\n\nИсточник: kotiki://plan/[A-Za-z0-9_-]{1,128}/[A-Za-z0-9_-]{1,128}\z""").containsMatchIn(text))return false
    if(Regex("""\n\n(?:Источник: )?kotiki://chat/[A-Za-z0-9_-]{1,300}/message/[A-Za-z0-9_-]{1,200}\z""").containsMatchIn(text))return false
    // Unlinked imported/selected text is private until the user explicitly shares the new task.
    return true
}
