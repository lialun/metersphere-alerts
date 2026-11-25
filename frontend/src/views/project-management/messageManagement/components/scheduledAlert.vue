<template>
  <a-button v-permission="['PROJECT_MESSAGE:READ+UPDATE']" class="mr-3" @click="open">
    {{ t('project.scheduledAlert.settings') }}
  </a-button>
  <a-modal v-model:visible="visible" :title="t('project.scheduledAlert.settings')" :width="700" :on-before-ok="save">
    <a-spin :loading="loading" class="w-full">
      <a-form :model="form" layout="vertical">
        <a-form-item :label="t('project.scheduledAlert.type')">
          <a-select :model-value="taskType" @change="changeType">
            <a-option value="API_DEFINITION_TASK">{{ t('project.scheduledAlert.case') }}</a-option>
            <a-option value="API_SCENARIO_TASK">{{ t('project.scheduledAlert.scenario') }}</a-option>
            <a-option value="TEST_PLAN_TASK">{{ t('project.scheduledAlert.plan') }}</a-option>
          </a-select>
        </a-form-item>
        <a-alert class="mb-4">{{ t('project.scheduledAlert.scope') }}</a-alert>
        <a-form-item :label="t('project.scheduledAlert.threshold')">
          <a-input-number v-model="form.failureThreshold" :min="1" :max="100" :precision="0" />
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model="form.repeatNotifications">{{ t('project.scheduledAlert.repeat') }}</a-checkbox>
        </a-form-item>
        <a-form-item :label="t('project.scheduledAlert.template')">
          <a-textarea
            v-model="form.failureTemplate"
            :max-length="4000"
            :auto-size="{ minRows: 7, maxRows: 15 }"
            :placeholder="t('project.scheduledAlert.defaultTemplate')"
          />
        </a-form-item>
        <div class="mb-3 text-[var(--color-text-3)]">{{ t('project.scheduledAlert.variables') }}</div>
        <div class="mb-4 flex flex-wrap gap-2">
          <a-button v-for="variable in variables" :key="variable" size="mini" @click="insertVariable(variable)">
            {{ variable }}
          </a-button>
        </div>
        <a-button type="text" @click="form.failureTemplate = defaultTemplate">{{
          t('project.scheduledAlert.useExample')
        }}</a-button>
        <div class="my-2 font-medium">{{ t('common.preview') }}</div>
        <pre class="whitespace-pre-wrap break-all rounded bg-[var(--color-fill-2)] p-3">{{ preview }}</pre>
      </a-form>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
  import { computed, reactive, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';

  import { getScheduledAlert, saveScheduledAlert } from '@/api/modules/project-management/messageManagement';
  import { useI18n } from '@/hooks/useI18n';
  import useAppStore from '@/store/modules/app';

  const { t } = useI18n();
  const appStore = useAppStore();
  const visible = ref(false);
  const loading = ref(false);
  const ready = ref(false);
  let generation = 0;
  const taskType = ref('API_SCENARIO_TASK');
  const variables = [
    'projectName',
    'resourceName',
    'environment',
    'reportName',
    'reportUrl',
    'failedCount',
    'failureDetails',
    'consecutiveFailures',
    'threshold',
  ];
  /* eslint-disable no-template-curly-in-string -- Literal MeterSphere notification placeholders. */
  const defaultTemplate =
    '${projectName} / ${resourceName}\nEnvironment: ${environment}\nConsecutive failures: ${consecutiveFailures} (threshold: ${threshold})\nFailed requests: ${failedCount}\n${failureDetails}\nReport: ${reportUrl}';
  /* eslint-enable no-template-curly-in-string */
  const form = reactive({ revision: 0, failureThreshold: 1, repeatNotifications: true, failureTemplate: '' });
  const preview = computed(() => {
    const values: Record<string, string | number> = {
      projectName: 'Demo',
      resourceName: 'Scheduled API check',
      environment: 'Test',
      reportName: 'API report',
      reportUrl: 'https://metersphere.example/#/api-test/report',
      failedCount: 1,
      failureDetails:
        'Login API | POST https://api.example/login | HTTP 503 | Expected HTTP 200: expected 200, actual 503',
      consecutiveFailures: form.failureThreshold,
      threshold: form.failureThreshold,
    };
    return (form.failureTemplate || defaultTemplate).replace(/\$\{([^{}]+)}/g, (match, key) =>
      String(values[key] ?? match)
    );
  });
  async function load() {
    const requestGeneration = ++generation;
    loading.value = true;
    ready.value = false;
    try {
      const policy = await getScheduledAlert(appStore.currentProjectId, taskType.value);
      if (requestGeneration === generation) {
        Object.assign(form, policy);
        ready.value = true;
      }
    } finally {
      if (requestGeneration === generation) loading.value = false;
    }
  }
  async function open() {
    visible.value = true;
    await load();
  }
  async function changeType(value: string | number | boolean | Record<string, any>) {
    taskType.value = String(value);
    await load();
  }
  function insertVariable(variable: string) {
    form.failureTemplate += `\${${variable}}`;
  }
  watch(
    () => appStore.currentProjectId,
    () => {
      generation += 1;
      ready.value = false;
      visible.value = false;
    }
  );
  async function save() {
    if (
      !ready.value ||
      loading.value ||
      !Number.isInteger(form.failureThreshold) ||
      form.failureThreshold < 1 ||
      form.failureThreshold > 100
    ) {
      return false;
    }
    loading.value = true;
    try {
      await saveScheduledAlert({ ...form, projectId: appStore.currentProjectId, taskType: taskType.value });
      Message.success(t('common.saveSuccess'));
      return true;
    } catch {
      return false;
    } finally {
      loading.value = false;
    }
  }
</script>
