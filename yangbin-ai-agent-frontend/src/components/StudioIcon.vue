<script setup>
defineProps({ name: String, size: { type: Number, default: 22 } })
const paths = {
spark: 'm12 3 2.6 6.4L21 12l-6.4 2.6L12 21l-2.6-6.4L3 12l6.4-2.6L12 3Z',
search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',
plus: 'M12 5v14M5 12h14', arrow: 'M5 12h14m-6-6 6 6-6 6', close: 'm6 6 12 12M6 18 18 6',
layers: 'm12 3 10 5-10 5L2 8l10-5Zm-10 9 10 5 10-5M2 16l10 5 10-5',
notes: 'M5 3h14v18H5V3Zm4 5h6m-6 4h6m-6 4h4',
document: 'M14 2H5v20h14V7l-5-5Zm0 0v6h5M8 12h8m-8 4h6',
slides: 'M3 3h18v13H3V3Zm9 13v5m-5 0h10M7 8h10m-10 4h6',
pen: 'm15 4 5 5M4 20l5-1L21 7l-5-5L4 14v6Z',
video: 'M3 5h12v14H3V5Zm12 5 6-3v10l-6-3',
campaign: 'M3 9h5l12-5v16L8 15H3V9Zm5 6 2 6H6l-2-6',
bag: 'M4 7h16l1 14H3L4 7Zm4 0V5a4 4 0 0 1 8 0v2',
calendar: 'M4 5h16v16H4V5Zm4-3v6m8-6v6M4 11h16m-12 4h2m4 0h2',
layout: 'M3 3h18v18H3V3Zm0 6h18M9 9v12',
chart: 'M4 3v18h17M8 17v-6m5 6V6m5 11V9',
book: 'M12 5C8 2 4 3 2 4v16c4-2 7-1 10 1 3-2 6-3 10-1V4c-2-1-6-2-10 1Zm0 0v16',
bulb: 'M9 18h6m-6 3h6M8 14a7 7 0 1 1 8 0c-1 1-1 2-1 2H9s0-1-1-2Z',
person: 'M20 21v-2a8 8 0 0 0-16 0v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
chat: 'M21 11a9 9 0 0 1-9 9H3l2-4a9 9 0 1 1 16-5Z',
map: 'm3 5 6-3 6 3 6-3v17l-6 3-6-3-6 3V5Zm6-3v17m6-14v17',
grid: 'M3 3h7v7H3V3Zm11 0h7v7h-7V3ZM3 14h7v7H3v-7Zm11 0h7v7h-7v-7Z',
plug: 'M8 2v5m8-5v5M5 7h14v3a7 7 0 0 1-14 0V7Zm7 10v5',
check: 'm5 12 4 4L19 6', clock: 'M12 7v5l4 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
folder: 'M3 5h7l2 3h9v13H3V5Z', menu: 'M4 6h16M4 12h16M4 18h16',
download: 'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5', copy: 'M8 8h13v13H8V8ZM4 16H2V2h14v2'
}
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.spark" /></svg></template>
