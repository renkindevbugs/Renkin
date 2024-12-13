package com.kaanelloed.iconeration.apk

import com.reandroid.dex.common.AccessFlag
import com.reandroid.dex.common.AnnotationVisibility
import com.reandroid.dex.common.Modifier
import com.reandroid.dex.debug.DebugElementType
import com.reandroid.dex.ins.Opcode
import com.reandroid.dex.ins.RegistersSet
import com.reandroid.dex.key.FieldKey
import com.reandroid.dex.key.MethodKey
import com.reandroid.dex.key.StringKey
import com.reandroid.dex.key.TypeKey
import com.reandroid.dex.sections.SectionList
import com.reandroid.dex.sections.SectionType
import com.reandroid.dex.value.DexValueType

class DexClassBuilder(private val sectionList: SectionList) {
    fun buildRClass() {
        val classKey = TypeKey("Lcom/kaanelloed/iconerationiconpack/R;")

        val classId = sectionList.getOrCreateSectionItem(
            SectionType.CLASS_ID, classKey)

        classId.setKey(classKey)
        classId.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.FINAL)
        classId.setSuperClass(TypeKey("Ljava/lang/Object;"))
        classId.setSourceFile(null)
        classId.setInterfaces(null)

        //Direct Method
        val classData = classId.orCreateClassData
        val method = classData.getOrCreateDirect(MethodKey(classKey, "<init>", null, "V"))
        method.accessFlagsValue = getFlagsValue(AccessFlag.PRIVATE, AccessFlag.CONSTRUCTOR)

        val codeItem = method.orCreateCodeItem
        codeItem.registersCount = 1
        codeItem.parameterRegistersCount = 1
        val instructions = codeItem.instructionList

        val ins = instructions.createAt(0, Opcode.INVOKE_DIRECT)
        val reg = ins as RegistersSet
        reg.registersCount = 1
        reg.setRegister(0, 0)
        ins.sectionIdKey = MethodKey("Ljava/lang/Object;", "<init>", null, "V")

        instructions.createAt(1, Opcode.RETURN_VOID)

        //Annotation
        val annotation = classId.orCreateClassAnnotations
        val item = annotation.addNewItem(TypeKey("Ldalvik/annotation/MemberClasses;"))
        item.visibility = AnnotationVisibility.SYSTEM
        val annotationElement = item.createNewElement()
        annotationElement.name = "value"
        val array = annotationElement.getOrCreateValue(DexValueType.ARRAY)
        val type = array.createNext(DexValueType.TYPE)
        type.setItem(TypeKey("Lcom/kaanelloed/iconerationiconpack/R\$layout;"))
    }

    fun buildRLayoutClass(resourceId: Int) {
        val classKey = TypeKey("Lcom/kaanelloed/iconerationiconpack/R\$layout;")

        val classId = sectionList.getOrCreateSectionItem(
            SectionType.CLASS_ID, classKey)

        classId.setKey(classKey)
        classId.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.FINAL)
        classId.setSuperClass(TypeKey("Ljava/lang/Object;"))
        classId.setSourceFile(null)
        classId.setInterfaces(null)

        val classData = classId.orCreateClassData

        //Field
        val field = classData.getOrCreateStatic(FieldKey(classKey.typeName, "main_activity", "I"))
        field.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL)
        field.getOrCreateStaticValue(DexValueType.INT).data = resourceId

        //Direct Method
        val method = classData.getOrCreateDirect(MethodKey(classKey, "<init>", null, "V"))
        method.accessFlagsValue = getFlagsValue(AccessFlag.PRIVATE, AccessFlag.CONSTRUCTOR)

        val codeItem = method.orCreateCodeItem
        codeItem.registersCount = 1
        codeItem.parameterRegistersCount = 1
        val instructions = codeItem.instructionList

        val ins = instructions.createAt(0, Opcode.INVOKE_DIRECT)
        val reg = ins as RegistersSet
        reg.registersCount = 1
        reg.setRegister(0, 0)
        ins.sectionIdKey = MethodKey("Ljava/lang/Object;", "<init>", null, "V")

        instructions.createAt(1, Opcode.RETURN_VOID)

        //Annotation
        val annotation = classId.orCreateClassAnnotations
        val item = annotation.addNewItem(TypeKey("Ldalvik/annotation/EnclosingClass;"))
        item.visibility = AnnotationVisibility.SYSTEM
        val annotationElement = item.createNewElement()
        annotationElement.name = "value"
        val type = annotationElement.getOrCreateValue(DexValueType.TYPE)
        type.setItem(TypeKey("Lcom/kaanelloed/iconerationiconpack/R;"))

        val annotation2 = classId.orCreateClassAnnotations
        val item2 = annotation2.addNewItem(TypeKey("Ldalvik/annotation/InnerClass;"))
        item2.visibility = AnnotationVisibility.SYSTEM
        val annotationElement2 = item2.createNewElement()
        annotationElement2.name = "accessFlags"
        val type2 = annotationElement2.getOrCreateValue(DexValueType.INT)
        type2.data = 0x19
        val annotationElement3 = item2.createNewElement()
        annotationElement3.name = "name"
        val type3 = annotationElement3.getOrCreateValue(DexValueType.STRING)
        type3.data = "layout"
    }

    fun buildMainActivityClass(resourceId: Int) {
        val classKey = TypeKey("Lcom/kaanelloed/iconerationiconpack/MainActivity;")

        val classId = sectionList.getOrCreateSectionItem(
            SectionType.CLASS_ID, classKey)

        classId.setKey(classKey)
        classId.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC)
        classId.setSuperClass(TypeKey("Landroid/app/Activity;"))
        classId.setSourceFile("MainActivity.java")
        classId.setInterfaces(null)

        val classData = classId.orCreateClassData

        //Direct Method
        val method = classData.getOrCreateDirect(MethodKey(classKey, "<init>", null, "V"))
        method.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.CONSTRUCTOR)

        val codeItem = method.orCreateCodeItem
        codeItem.registersCount = 1
        codeItem.parameterRegistersCount = 1
        val instructions = codeItem.instructionList

        val ins = instructions.createAt(0, Opcode.INVOKE_DIRECT)
        val reg = ins as RegistersSet
        reg.registersCount = 1
        reg.setRegister(0, 0)
        ins.sectionIdKey = MethodKey("Landroid/app/Activity;", "<init>", null, "V")

        instructions.createAt(1, Opcode.RETURN_VOID)

        val debug = codeItem.orCreateDebugInfo
        debug.debugSequence.createNext(DebugElementType.LINE_NUMBER).also { it.lineNumber = 5 }

        //Virtual Method
        val vMethod = classData.getOrCreateVirtual(MethodKey(classKey, "onCreate", arrayOf("Landroid/os/Bundle;"), "V"))
        vMethod.accessFlagsValue = getFlagsValue(AccessFlag.PROTECTED)

        val codeItem2 = vMethod.orCreateCodeItem
        codeItem2.registersCount = 3
        codeItem2.parameterRegistersCount = 2
        val instructions2 = codeItem2.instructionList

        val ins2 = instructions2.createAt(0, Opcode.INVOKE_SUPER)
        val reg2 = ins2 as RegistersSet
        reg2.registersCount = 2
        reg2.setRegister(0, 1)
        reg2.setRegister(1, 2)
        ins2.sectionIdKey = MethodKey("Landroid/app/Activity;", "onCreate", arrayOf("Landroid/os/Bundle;"), "V")

        val ins3 = instructions2.createAt(1, Opcode.CONST_HIGH16)
        val reg3 = ins3 as RegistersSet
        reg3.registersCount = 1
        reg3.setRegister(0, 0)
        ins3.data = resourceId

        val ins4 = instructions2.createAt(2, Opcode.INVOKE_VIRTUAL)
        val reg4 = ins4 as RegistersSet
        reg4.registersCount = 2
        reg4.setRegister(0, 1)
        reg4.setRegister(1, 0)
        ins4.sectionIdKey = MethodKey(classKey, "setContentView", arrayOf("I"), "V")

        instructions2.createAt(3, Opcode.RETURN_VOID)

        val debug2 = codeItem2.orCreateDebugInfo
        debug2.debugSequence.createNext(DebugElementType.LINE_NUMBER).also { it.lineNumber = 9 }
        debug2.debugSequence.createNext(DebugElementType.LINE_NUMBER).also { it.lineNumber = 10 }
        debug2.debugSequence.createNext(DebugElementType.LINE_NUMBER).also { it.lineNumber = 11 }

        vMethod.getParameter(0).debugName = "savedInstanceState"
    }

    fun buildBuildConfig() {
        val classKey = TypeKey("Lcom/kaanelloed/iconerationiconpack/BuildConfig;")

        val classId = sectionList.getOrCreateSectionItem(
            SectionType.CLASS_ID, classKey)

        classId.setKey(classKey)
        classId.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC)
        classId.setSuperClass(TypeKey("Ljava/lang/Object;"))
        classId.setSourceFile("BuildConfig.java")
        classId.setInterfaces(null)

        val classData = classId.orCreateClassData

        //Field
        val appId = classData.getOrCreateStatic(FieldKey(classKey.typeName, "APPLICATION_ID", "Ljava/lang/String;"))
        appId.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL)
        appId.getOrCreateStaticValue(DexValueType.STRING).data = "com.kaanelloed.iconerationiconpack"

        val buildType = classData.getOrCreateStatic(FieldKey(classKey.typeName, "BUILD_TYPE", "Ljava/lang/String;"))
        buildType.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL)
        buildType.getOrCreateStaticValue(DexValueType.STRING).data = "debug"

        val debugField = classData.getOrCreateStatic(FieldKey(classKey.typeName, "DEBUG", "Z"))
        debugField.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL)

        val versionCode = classData.getOrCreateStatic(FieldKey(classKey.typeName, "VERSION_CODE", "I"))
        versionCode.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL)
        versionCode.getOrCreateStaticValue(DexValueType.INT).data = 1

        val versionName = classData.getOrCreateStatic(FieldKey(classKey.typeName, "VERSION_NAME", "Ljava/lang/String;"))
        versionName.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL)
        versionName.getOrCreateStaticValue(DexValueType.STRING).data = "0.1.0"

        //Direct Method
        val method = classData.getOrCreateDirect(MethodKey(classKey, "<clinit>", null, "V"))
        method.accessFlagsValue = getFlagsValue(AccessFlag.STATIC, AccessFlag.CONSTRUCTOR)

        val codeItem = method.orCreateCodeItem
        codeItem.registersCount = 1
        codeItem.parameterRegistersCount = 0
        val instructions = codeItem.instructionList

        val ins = instructions.createAt(0, Opcode.CONST_STRING)
        val reg = ins as RegistersSet
        reg.registersCount = 1
        reg.setRegister(0, 0)
        ins.sectionIdKey = StringKey("true")

        val ins3 = instructions.createAt(1, Opcode.INVOKE_DIRECT)
        val reg3 = ins3 as RegistersSet
        reg3.registersCount = 1
        reg3.setRegister(0, 0)
        ins3.sectionIdKey = MethodKey("Ljava/lang/Boolean;", "parseBoolean", arrayOf("Ljava/lang/String;"), "Z")

        val ins4 = instructions.createAt(2, Opcode.MOVE_RESULT)
        val reg4 = ins4 as RegistersSet
        reg4.registersCount = 1
        reg4.setRegister(0, 0)

        val ins5 = instructions.createAt(3, Opcode.SPUT_BOOLEAN)
        val reg5 = ins5 as RegistersSet
        reg5.registersCount = 1
        reg5.setRegister(0, 0)
        ins5.sectionIdKey = FieldKey("Lcom/kaanelloed/iconerationiconpack/BuildConfig;", "DEBUG", "Z")

        instructions.createAt(4, Opcode.RETURN_VOID)

        val debug = codeItem.orCreateDebugInfo
        debug.debugSequence.createNext(DebugElementType.LINE_NUMBER).also { it.lineNumber = 7 }

        // ---
        val method2 = classData.getOrCreateDirect(MethodKey(classKey, "<init>", null, "V"))
        method2.accessFlagsValue = getFlagsValue(AccessFlag.PUBLIC, AccessFlag.CONSTRUCTOR)

        val codeItem2 = method2.orCreateCodeItem
        codeItem2.registersCount = 1
        codeItem2.parameterRegistersCount = 1
        val instructions2 = codeItem2.instructionList

        val ins2 = instructions2.createAt(0, Opcode.INVOKE_DIRECT)
        val reg2 = ins2 as RegistersSet
        reg2.registersCount = 1
        reg2.setRegister(0, 0)
        ins2.sectionIdKey = MethodKey("Ljava/lang/Object;", "<init>", null, "V")

        instructions2.createAt(1, Opcode.RETURN_VOID)

        val debug2 = codeItem2.orCreateDebugInfo
        debug2.debugSequence.createNext(DebugElementType.LINE_NUMBER).also { it.lineNumber = 6 }
    }

    private fun getFlagsValue(vararg flags: AccessFlag): Int {
        return Modifier.combineValues(flags)
    }
}