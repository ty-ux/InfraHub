<template>
  <main class="account-page">
    <el-card shadow="never">
      <h1>{{ title }}</h1>
      <slot />
    </el-card>
  </main>
</template>

<script setup lang="ts">
defineProps<{ title: string }>();
</script>

<style scoped>
.account-page {
  display: grid;
  min-height: 100dvh;
  align-content: center;
  justify-items: center;
  padding: var(--space-lg);
}
.account-page :deep(.el-card) {
  width: min(100%, 470px);
}
h1 {
  margin: 0 0 var(--space-md);
}
.account-page :deep(.account-actions) {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-xs);
  margin-top: var(--space-md);
}
.account-page :deep(.account-help) {
  color: var(--el-text-color-secondary);
}
</style>
