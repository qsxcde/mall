import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'
import globals from 'globals'

/**
 * ESLint 扁平配置（ESLint 9）。
 *
 * 设计取舍：
 *  - 只做**静态检查**（可能出错的写法），格式交给 Prettier ——
 *    末尾的 `prettier` 会把与 Prettier 冲突的格式类规则全部关掉，
 *    避免"两个工具互相改对方的格式"。
 *  - `vue/multi-word-component-names` 关掉：本项目视图文件名就是单单词（HomeView/CartView 之类），
 *    开启只会得到一堆无意义告警。
 *  - `no-empty` 允许空 catch：项目里有几处刻意"吞掉异常只记日志"的降级分支。
 */
export default [
  { ignores: ['dist/**', 'node_modules/**', 'public/**'] },

  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  prettier,

  {
    files: ['**/*.{js,mjs,cjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      'no-empty': ['error', { allowEmptyCatch: true }],
      // ESLint 9 把 no-unused-vars 的 caughtErrors 默认值由 'none' 改成了 'all'，
      // 于是项目里大量「刻意忽略异常」的 catch (e) 被全量报出来 ——
      // 它们是「读缓存失败就回退默认值」「隐私模式写入失败就忽略」这类降级分支，
      // 本来就不关心异常对象。这里显式恢复 'none'
      //（不是为了让 CI 变绿而放水：真正未使用的变量/导入仍然会被报出来）。
      //
      // `^_` 前缀忽略：`const { list: _list, total: _total, ...rest } = res` 这种
      // 「解构出来的字段我就是要丢掉」是 JS 社区通行写法，用下划线显式表达「有意不用」。
      'no-unused-vars': [
        'error',
        {
          caughtErrors: 'none',
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
        },
      ],
    },
  },
]
