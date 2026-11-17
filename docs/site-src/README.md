# InfraHub 使用指南

编辑 `guide.mjs` 更新功能目录与使用说明，编辑 `home.html` 更新首页内容。运行以下命令生成静态 HTML。

```sh
node docs/site-src/build.mjs
```

样式、导航脚本与截图放在 `docs/site/`。可直接打开本地的 `docs/site/index.html` 预览。GitHub Pages 发布 `gh-pages` 分支的根目录，更新页面时同步发布 `docs/site/` 的内容。
