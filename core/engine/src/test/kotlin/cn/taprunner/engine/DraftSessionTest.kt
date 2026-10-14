package cn.taprunner.engine

import cn.taprunner.model.*
import kotlin.test.*

class DraftSessionTest {
    private class Library {
        val original=Script(id="original",name="原脚本",steps=listOf(Step("first"),Step("second",x=.3,y=.4)))
        var scripts=listOf(original,Script(id="other",name="其他",steps=listOf(Step("o"))))
        var selected="original"
        var writes=0
        val draft=DraftSession({scripts},{scripts.firstOrNull {it.id==selected}},{selected=it},{script -> scripts=scripts.filter {it.id!=script.id}+script;selected=script.id;writes++})
    }
    @Test fun openingDoesNotWrite(){val l=Library();assertEquals(l.original,l.draft.begin());assertFalse(l.draft.dirty);assertEquals(0,l.writes)}
    @Test fun previewDoesNotModifySaved(){val l=Library();l.draft.update(l.original.copy(name="草稿"));assertTrue(l.draft.dirty);assertEquals("草稿",l.draft.current()?.name);assertEquals("原脚本",l.scripts.first().name);assertEquals(0,l.writes)}
    @Test fun explicitCommitWritesOnce(){val l=Library();val s=l.original.copy(name="保存名称");l.draft.update(s);l.draft.commit();assertEquals(1,l.writes);assertEquals(s,l.scripts.first {it.id==s.id});assertFalse(l.draft.dirty)}
    @Test fun discardRestoresSavedWithoutWrite(){val l=Library();l.draft.update(l.original.copy(steps=emptyList()));l.draft.discard();assertEquals(l.original,l.draft.current());assertEquals(0,l.writes);assertFalse(l.draft.dirty)}
    @Test fun newScriptIsNotSavedUntilCommit(){val l=Library();val created=l.draft.create();assertTrue(l.draft.dirty);assertTrue(l.scripts.none {it.id==created.id});l.draft.commit();assertTrue(l.scripts.any {it.id==created.id});assertEquals(1,l.writes)}
    @Test fun discardNewDoesNotDeleteExisting(){val l=Library();l.draft.create();l.draft.discard();assertEquals(2,l.scripts.size);assertEquals(l.original,l.draft.current());assertEquals(0,l.writes)}
    @Test fun cannotSwitchWithUnsavedChanges(){val l=Library();l.draft.update(l.original.copy(name="修改"));assertFailsWith<IllegalArgumentException>{l.draft.select("other")};assertEquals("original",l.selected);assertEquals("修改",l.draft.current()?.name)}
    @Test fun savedDraftCanSwitch(){val l=Library();l.draft.begin();l.draft.select("other");assertEquals("other",l.selected);assertEquals("other",l.draft.current()?.id);assertFalse(l.draft.dirty)}
    @Test fun cannotReplaceDirtyWithNew(){val l=Library();l.draft.update(l.original.copy(name="修改"));assertFailsWith<IllegalArgumentException>{l.draft.create()};assertEquals("修改",l.draft.current()?.name)}
    @Test fun conflictingExternalEditCannotBeOverwritten(){val l=Library();l.draft.update(l.original.copy(name="草稿"));l.scripts=l.scripts.map {if(it.id=="original")it.copy(name="主界面修改")else it};assertFailsWith<IllegalArgumentException>{l.draft.commit()};assertEquals("主界面修改",l.scripts.first().name);assertEquals(0,l.writes)}
    @Test fun discardKeepsLatestExternalEdit(){val l=Library();l.draft.update(l.original.copy(name="草稿"));l.scripts=l.scripts.map {if(it.id=="original")it.copy(name="最新保存")else it};l.draft.discard();assertEquals("最新保存",l.draft.current()?.name);assertEquals(0,l.writes)}
    @Test fun deletedSourceCannotBeResurrected(){val l=Library();l.draft.update(l.original.copy(name="草稿"));l.scripts=l.scripts.filter {it.id!="original"};assertFailsWith<IllegalArgumentException>{l.draft.commit()};assertEquals(0,l.writes)}
    @Test fun reorderKeepsIdsAndDiscardRestoresOrder(){val l=Library();l.draft.update(l.original.copy(steps=l.original.steps.reversed()));assertEquals(listOf("second","first"),l.draft.current()?.steps?.map {it.id});l.draft.discard();assertEquals(listOf("first","second"),l.draft.current()?.steps?.map {it.id})}
    @Test fun invalidCoordinatesCannotEnterPreview(){val l=Library();assertFailsWith<IllegalArgumentException>{l.draft.update(l.original.copy(steps=listOf(Step("bad",x=2.0))))};assertFalse(l.draft.dirty)}
    @Test fun invalidEmptyScriptMetadataRejected(){val l=Library();val s=l.draft.create();assertFailsWith<IllegalArgumentException>{l.draft.update(s.copy(meta=Meta(minIntervalMs=0)))}}
    @Test fun switchingToMissingScriptKeepsCurrent(){val l=Library();l.draft.begin();assertFailsWith<IllegalArgumentException>{l.draft.select("missing")};assertEquals(l.original,l.draft.current())}
}
