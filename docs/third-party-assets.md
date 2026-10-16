# Third-Party Assets

This document lists the third-party client-side assets bundled or vendored in FastBite to ensure all assets are served directly from the application without relying on external CDNs (preventing third-party IP leakage).

## Vendored Assets

### Fonts

- **DM Sans**
  - **Source**: Google Fonts (https://fonts.google.com/specimen/DM+Sans) / https://github.com/googlefonts/dm-fonts
  - **Files**:
    - `fastbite/adapter-in/web/src/main/resources/static/css/fonts/dm-sans-latin.woff2`
    - `fastbite/adapter-in/web/src/main/resources/static/css/fonts/dm-sans-latin-ext.woff2`
  - **Subsets**: latin, latin-ext
  - **License**: SIL Open Font License, Version 1.1 (OFL 1.1)
  - **Copyright**: Copyright 2014 The DM Sans Project Authors (https://github.com/googlefonts/dm-fonts)
  - **License File**: `fastbite/adapter-in/web/src/main/resources/static/css/fonts/OFL.txt`

- **Manrope**
  - **Source**: Google Fonts (https://fonts.google.com/specimen/Manrope) / https://github.com/sharanda/manrope
  - **Files**:
    - `fastbite/adapter-in/web/src/main/resources/static/css/fonts/manrope-latin.woff2`
    - `fastbite/adapter-in/web/src/main/resources/static/css/fonts/manrope-latin-ext.woff2`
  - **Subsets**: latin, latin-ext
  - **License**: SIL Open Font License, Version 1.1 (OFL 1.1)
  - **Copyright**: Copyright 2018 The Manrope Project Authors (https://github.com/sharanda/manrope)
  - **License File**: `fastbite/adapter-in/web/src/main/resources/static/css/fonts/OFL.txt`

### JavaScript Libraries

- **qrcode-generator**
  - **Version**: 1.4.4
  - **Source**: https://cdnjs.cloudflare.com/ajax/libs/qrcode-generator/1.4.4/qrcode.min.js / https://github.com/kazuhikoarase/qrcode-generator
  - **File**: `fastbite/adapter-in/web/src/main/resources/static/js/qrcode.min.js`
  - **Size / SHA-256**: 20,387 bytes (`164fc2c1c9eaf0a03aa8dfdb855e82e41a5c6922fbad8bb3238116207e26bef7`)
  - **Author**: Kazuhiko Arase
  - **License**: MIT License (https://github.com/kazuhikoarase/qrcode-generator/blob/master/LICENSE)

## WebJars (Build Dependencies)

The following UI libraries are included via Maven build dependencies (WebJars) and served locally via Spring Boot's WebJars resource handler:

- **Bootstrap**: 5.3.0 (`org.webjars:bootstrap:5.3.0`, MIT License)
- **Font Awesome**: 6.4.0 (`org.webjars:font-awesome:6.4.0`, Font Awesome Free License - SIL OFL 1.1 for webfonts, MIT for code)
