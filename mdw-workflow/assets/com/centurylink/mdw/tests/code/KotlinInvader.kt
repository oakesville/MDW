package com.centurylink.mdw.tests.code

import com.centurylink.mdw.model.workflow.ActivityRuntimeContext
import com.centurylink.mdw.util.log.StandardLogger
import com.centurylink.mdw.util.timer.Tracked
import com.centurylink.mdw.workflow.activity.DefaultActivityImpl
import com.centurylink.mdw.annotations.Activity;

@Activity(value="Kotlin Invader", icon="com.centurylink.mdw.tests.code/invader.png",
        pagelet="com.centurylink.mdw.tests.code/KotlinInvader.pagelet")
class KotlinInvader : DefaultActivityImpl() {

    override fun execute(runtimeContext: ActivityRuntimeContext): Any? {
        setValue("updated", "updated from kotlin")
        setValue("invader", getAttribute("Invader"))
        return getAttribute("Planet")
    }
}
