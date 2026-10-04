<script setup>
defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 20 },
})
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    class="app-icon"
    aria-hidden="true"
  >
    <!-- 公司知识助手 -->
    <g v-if="name === 'bot'">
      <rect x="4.5" y="9" width="15" height="10" rx="3.2" />
      <path d="M12 9V5.5" />
      <circle cx="12" cy="4" r="1.15" fill="currentColor" stroke="none" />
      <circle cx="9" cy="14" r="1.15" fill="currentColor" stroke="none" />
      <circle cx="15" cy="14" r="1.15" fill="currentColor" stroke="none" />
      <path d="M9 17.2h6" />
      <path d="M2.5 12.5v3" />
      <path d="M21.5 12.5v3" />
    </g>

    <!-- 发送 -->
    <path v-else-if="name === 'send'" d="M3.4 11.6 20.8 3.9 13.1 21.3l-2.5-7.4-7.2-2.3z" />

    <!-- 用户 -->
    <g v-else-if="name === 'user'">
      <circle cx="12" cy="8" r="3.4" />
      <path d="M5 20c1.1-3.6 4-5.4 7-5.4s5.9 1.8 7 5.4" />
    </g>

    <!-- 新建会话 -->
    <g v-else-if="name === 'plus'">
      <path d="M12 5v14" />
      <path d="M5 12h14" />
    </g>

    <!-- 停止生成 -->
    <rect v-else-if="name === 'stop'" x="6" y="6" width="12" height="12" rx="2" />
  </svg>
</template>

<style scoped>
.app-icon {
  display: block;
  flex-shrink: 0;
}
</style>
