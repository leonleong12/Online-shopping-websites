module.exports = async function checkSupportDefaults(page, { screenshotPath } = {}) {
  await page.getByText('AI 导购参数配置', { exact: false }).click()
  const assert = (condition, message) => { if (!condition) throw new Error(message) }
  const url = page.getByRole('textbox', { name: 'API 地址', exact: true })
  const model = page.getByRole('textbox', { name: '模型', exact: true })
  const protocol = page.getByRole('combobox', { name: 'API 协议', exact: true })
  const welcome = page.getByRole('textbox', { name: '欢迎语', exact: true })

  assert(await url.inputValue() === '', 'API URL must be blank')
  assert(await model.inputValue() === '', 'Model must be blank')
  assert(await protocol.inputValue() === 'CHAT_COMPLETIONS', 'Protocol must default to Chat Completions')
  assert(await welcome.inputValue() === '您好，欢迎来到 NovaMart。今天想为日常添置点什么好物？', 'Unicode greeting is incorrect')

  let saved = null
  await page.route('**/api/admin/support/config', async route => {
    if (route.request().method() === 'PUT') {
      saved = route.request().postDataJSON()
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ message: '客服配置已保存' }) })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        adminOnline: false,
        aiEnabled: false,
        aiBaseUrl: '',
        aiModel: '',
        aiProtocol: 'CHAT_COMPLETIONS',
        welcomeMessage: saved?.welcomeMessage || '您好，欢迎来到 NovaMart。今天想为日常添置点什么好物？',
        apiKeyConfigured: false
      })
    })
  })

  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await page.getByText('客服配置已保存', { exact: true }).waitFor()
  assert(saved?.aiBaseUrl === '' && saved?.aiModel === '' && saved?.aiProtocol === 'CHAT_COMPLETIONS', 'Blank settings save payload is incorrect')
  assert(saved.welcomeMessage === '您好，欢迎来到 NovaMart。今天想为日常添置点什么好物？', 'Greeting changed on save')
  assert(await url.inputValue() === '' && await model.inputValue() === '', 'Blank settings changed after save')

  if (screenshotPath) await page.screenshot({ path: screenshotPath, fullPage: true })
  await page.getByRole('button', { name: '刷新工作台', exact: true }).click()
  assert(await welcome.inputValue() === saved.welcomeMessage, 'Welcome changed after refresh')
  return 'PASS: blank API/model, Chat Completions default, Unicode welcome, and saved settings.'
}
