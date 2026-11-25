package team.kotiki.app

/** Agent APKs cannot delegate into a real participant identity or load a real workspace. */
internal fun testMembershipAllowed(agentBuild:Boolean,authUid:String,canonicalUid:String,testOnly:Boolean):Boolean =
    !agentBuild || (testOnly && authUid==canonicalUid)
