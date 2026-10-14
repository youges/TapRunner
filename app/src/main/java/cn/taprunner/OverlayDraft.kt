package cn.taprunner

import cn.taprunner.engine.DraftSession

class OverlayDraft(store:ScriptStore):DraftSession(
    readScripts={store.scripts.value},
    readSelected={store.current()},
    selectSaved={store.selected.value=it},
    writeSaved={store.save(it)}
)
