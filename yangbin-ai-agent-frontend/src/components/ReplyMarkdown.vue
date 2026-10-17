<script setup>
import {computed} from 'vue'
import {marked} from 'marked'
import DOMPurify from 'dompurify'
const props=defineProps({text:{type:String,default:''}})
const html=computed(()=>DOMPurify.sanitize(marked.parse(props.text,{breaks:true,gfm:true}),{FORBID_TAGS:['img','style','iframe','svg','form','input'],FORBID_ATTR:['style']}))
</script>
<template><div class="reply-markdown" v-html="html"></div></template>
<style>
.reply-markdown{font-size:15px;line-height:1.95;color:#252c37;overflow-wrap:anywhere}.reply-markdown p{margin:12px 0}.reply-markdown h1,.reply-markdown h2,.reply-markdown h3{font-weight:650;line-height:1.5;margin:24px 0 12px}.reply-markdown h1{font-size:24px}.reply-markdown h2{font-size:21px}.reply-markdown h3{font-size:18px}.reply-markdown ul,.reply-markdown ol{padding-left:25px;margin:12px 0}.reply-markdown li{margin:6px 0}.reply-markdown hr{border:0;border-top:1px solid #eceef2;margin:24px 0}.reply-markdown pre{white-space:pre;overflow-x:auto;background:#f6f7f9;border:1px solid #eceef2;border-radius:12px;padding:18px;line-height:1.65;margin:18px 0}.reply-markdown code{font-size:13px;background:#f4f5f7;padding:2px 4px;border-radius:4px}.reply-markdown pre code{padding:0;background:none}.reply-markdown table{display:block;max-width:100%;overflow-x:auto;border-collapse:collapse;margin:18px 0}.reply-markdown td,.reply-markdown th{border:1px solid #e3e7ee;padding:10px 16px;min-width:90px}.reply-markdown th{background:#f6f8fb;text-align:left}.reply-markdown blockquote{border-left:3px solid #a9b9e7;padding-left:18px;color:#798391}.reply-markdown a{color:#3768df;text-decoration:underline}
</style>
