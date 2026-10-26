import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeEditorPath, editorUrl, editorPreferenceKey, readEditorPath, saveEditorPath } from '../src/editor-link.js'
test('project URLs encode spaces, unicode and URL metacharacters without command injection', () => {
 assert.equal(editorUrl('"C:\\Projects\\My app #1%"'), 'vscode://file/C:/Projects/My%20app%20%231%25/')
 assert.equal(editorUrl('/home/dev/中文'), 'vscode://file/home/dev/%E4%B8%AD%E6%96%87/')
 assert.equal(normalizeEditorPath('F:\\projects\\demo\\'), 'F:/projects/demo')
 for(const path of ['relative','vscode://command/test','https://evil','C:/x\nfoo','C:/x/../y','//host/share','C:/','/','C:/x:12','C:/x?command=1']) assert.throws(()=>editorUrl(path),path)
})
test('preferences isolate owner/project, fail closed on damaged storage and clear explicitly', () => {
 const store=new Map();globalThis.localStorage={getItem:k=>store.get(k)??null,setItem:(k,v)=>store.set(k,v),removeItem:k=>store.delete(k)}
 saveEditorPath('a','p','C:/one');assert.equal(readEditorPath('a','p'),'C:/one');assert.equal(readEditorPath('b','p'),'');assert.equal(readEditorPath('a','q'),'')
 store.set(editorPreferenceKey('b','p'),'javascript:bad');assert.equal(readEditorPath('b','p'),'')
 assert.notEqual(editorPreferenceKey('a:b','c'),editorPreferenceKey('a','b:c'))
 saveEditorPath('a','p','');assert.equal(readEditorPath('a','p'),'')
 globalThis.localStorage={getItem(){throw Error('blocked')}};assert.equal(readEditorPath('a','p'),'');delete globalThis.localStorage
})
