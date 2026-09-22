<script setup lang="ts">
import LegalPageShell from "../components/LegalPageShell.vue";
</script>

<template>
  <LegalPageShell
    eyebrow="TERMS OF SERVICE"
    title="用户协议"
    description="注册、登录或使用本服务，表示你已阅读并同意本协议。"
  >
    <section>
      <h2>1. 服务说明</h2>
      <p>
        Training Plan 是处于试运行阶段的个人训练数据工具，用于汇总 Garmin
        训练与恢复数据、展示趋势并辅助制定训练计划。本服务不是医疗机构，也不提供医疗诊断或治疗。
      </p>
    </section>

    <section>
      <h2>2. 账号与使用资格</h2>
      <ul>
        <li>你应当年满 18 周岁，并使用真实、有效且由你控制的邮箱注册。</li>
        <li>你应妥善保管登录凭据，不得出借、转让账号或以他人身份使用服务。</li>
        <li>
          发现未授权使用、数据泄露或其他安全风险时，请立即停止使用并联系运营者。
        </li>
      </ul>
    </section>

    <section>
      <h2>3. Garmin 授权与数据</h2>
      <p>
        你只能绑定本人有权使用的 Garmin
        账号。你授权本服务按你的操作获取、存储和分析相关训练数据，
        可随时暂停自动同步或申请删除数据。Garmin 是独立第三方，本服务与 Garmin
        Ltd. 不存在隶属、代理或背书关系。
      </p>
    </section>

    <section>
      <h2>4. 规则与禁止行为</h2>
      <ul>
        <li>不得侵入、干扰、逆向破解、绕过访问控制或批量抓取本服务。</li>
        <li>不得上传违法、侵权、欺诈、恶意指令或可能危害系统安全的内容。</li>
        <li>不得利用本服务从事侵害他人权益或违反中国法律法规的活动。</li>
      </ul>
    </section>

    <section>
      <h2>5. 试运行、变更与中断</h2>
      <p>
        试运行期间可能进行功能调整、维护或限制使用。运营者会尽力保障服务连续性，但不承诺永不中断、永不出错或永久保留。
        如你严重违反本协议或危害系统安全，运营者可暂停或终止相关账号服务。
      </p>
    </section>

    <section>
      <h2>6. 知识产权</h2>
      <p>
        除你自行提供的内容及第三方拥有的标识外，本服务的程序、页面设计和原创文案受相关法律保护。
        Garmin 、DeepSeek 及其相关商标归各自权利人所有。
      </p>
    </section>

    <section>
      <h2>7. 适用法律与联系</h2>
      <p>
        本协议适用中华人民共和国法律。争议应首先协商解决；无法协商时，可依法向有管辖权的人民法院提起诉讼。
        问题、投诉或数据权利请求可通过
        <a
          href="https://github.com/17747377352/training-plan/issues"
          target="_blank"
          rel="noopener noreferrer"
        >
          项目公开联系渠道
        </a>
        提交；请勿在公开工单中填写密码、令牌或健康明细。
      </p>
    </section>
  </LegalPageShell>
</template>
