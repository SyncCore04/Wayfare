<template>
  <!-- 全局加载条（P5-D）：有请求在飞时出现在顶部。
       它替代了「每个页面自己维护 loading 标志」的散乱做法 ——
       那些标志要么漏加、要么忘了关，最后表现为「明明加载完了还在转」。
       只有一处真相（request.js 的 pendingCount），各页面不必各自为政。 -->
  <div v-if="pendingCount > 0" class="global-loading-bar"></div>
  <router-view />
</template>

<script setup>
import { pendingCount } from '@/api/request'
</script>

<style>
.global-loading-bar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: #409eff;
  z-index: 9999;
  animation: global-loading 1.2s ease-in-out infinite;
}

@keyframes global-loading {
  0% { transform: scaleX(0); transform-origin: left; }
  50% { transform: scaleX(1); transform-origin: left; }
  51% { transform: scaleX(1); transform-origin: right; }
  100% { transform: scaleX(0); transform-origin: right; }
}
</style>
