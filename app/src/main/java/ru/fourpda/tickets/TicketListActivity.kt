import os
import shutil
import subprocess
import argparse

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("!! Внимание: библиотека Pillow не найдена. Для работы с иконками, пожалуйста, установите ее:")
    print("   pip install Pillow")
    Image = None

def get_sdk_path():
    """Tries to find the Android SDK path."""
    # Check common environment variables
    sdk_path = os.environ.get('ANDROID_SDK_ROOT')
    if sdk_path and os.path.exists(sdk_path):
        return sdk_path
    
    sdk_path = os.environ.get('ANDROID_HOME')
    if sdk_path and os.path.exists(sdk_path):
        return sdk_path

    # Check default installation paths
    default_path = os.path.join(os.environ.get('LOCALAPPDATA'), 'Android', 'Sdk')
    if os.path.exists(default_path):
        return default_path
        
    print("!! Could not find Android SDK path.")
    print("!! Please set the ANDROID_SDK_ROOT environment variable.")
    return None

def generate_icons(icon_path, project_res_dir, background_color='#FFFFFF'):
    """Generates mipmap icons from a source image for both legacy and adaptive icon standards."""
    if not Image:
        print("!! Пропуск генерации иконок: библиотека Pillow не установлена.")
        return

    if not os.path.exists(icon_path):
        print(f"!! Файл иконки не найден по пути: {icon_path}")
        return

    try:
        source_image = Image.open(icon_path).convert("RGBA")
    except Exception as e:
        print(f"!! Не удалось открыть файл иконки: {e}")
        return

    print("--- Генерация иконок приложения (Legacy + Adaptive) ---")
    
    # --- 1. Generate Legacy Icons (for Android < 8.0) ---
    # Create a composite image with the background color
    composite_image = Image.new("RGBA", source_image.size, background_color)
    composite_image.paste(source_image, (0, 0), source_image)

    densities = {
        'mdpi': 48,
        'hdpi': 72,
        'xhdpi': 96,
        'xxhdpi': 144,
        'xxxhdpi': 192
    }

    for density, size in densities.items():
        mipmap_dir = os.path.join(project_res_dir, f'mipmap-{density}')
        os.makedirs(mipmap_dir, exist_ok=True)
        
        # Legacy square icon
        legacy_square = composite_image.resize((size, size), Image.Resampling.LANCZOS)
        legacy_square.save(os.path.join(mipmap_dir, 'ic_launcher.png'))

        # Legacy round icon
        mask = Image.new('L', (size, size), 0)
        draw = ImageDraw.Draw(mask)
        draw.ellipse((0, 0, size, size), fill=255)
        legacy_round = legacy_square.copy()
        legacy_round.putalpha(mask)
        legacy_round.save(os.path.join(mipmap_dir, 'ic_launcher_round.png'))

        # --- 2. Generate Adaptive Icon Foreground Layer (for Android >= 8.0) ---
        # The foreground layer should be smaller than the full icon size.
        # A common ratio is 72/108, so we resize to 2/3 of the full size.
        foreground_size = int(size * (2/3))
        # We resize the original source image, not the composite
        foreground_image = source_image.resize((foreground_size, foreground_size), Image.Resampling.LANCZOS)
        # Create a transparent canvas and paste the foreground in the center
        canvas = Image.new("RGBA", (size, size), (0,0,0,0))
        paste_pos = (size - foreground_size) // 2
        canvas.paste(foreground_image, (paste_pos, paste_pos), foreground_image)
        canvas.save(os.path.join(mipmap_dir, 'ic_launcher_foreground.png'))

    # --- 3. Set Adaptive Icon Background Color ---
    background_xml_path = os.path.join(project_res_dir, 'drawable', 'ic_launcher_background.xml')
    with open(background_xml_path, 'r', encoding='utf-8') as f:
        bg_content = f.read()
    bg_content = bg_content.replace('__ICON_BG_COLOR__', background_color)
    with open(background_xml_path, 'w', encoding='utf-8') as f:
        f.write(bg_content)

    print("--- Иконки успешно сгенерированы ---")

def create_signing_config(project_dir, keystore_path, keystore_password, key_alias, key_password):
    """Creates a signing.properties file for the signing configuration."""
    signing_props_path = os.path.join(project_dir, 'signing.properties')
    
    # Convert to absolute path and forward slashes for Gradle
    abs_keystore_path = os.path.abspath(keystore_path).replace('\\', '/')
    
    with open(signing_props_path, 'w', encoding='utf-8') as f:
        f.write(f'STORE_FILE={abs_keystore_path}\n')
        f.write(f'STORE_PASSWORD={keystore_password}\n')
        f.write(f'KEY_ALIAS={key_alias}\n')
        f.write(f'KEY_PASSWORD={key_password}\n')
    
    print("Created signing.properties with keystore configuration")

def modify_build_gradle_for_signing(build_gradle_path):
    """Modifies build.gradle to add signing configuration."""
    with open(build_gradle_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    new_lines = []
    inside_android = False
    inside_buildtypes = False
    inside_release = False
    signing_added = False
    buildtypes_indent = ''
    release_indent = ''
    brace_count = 0
    
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        
        # Detect android block
        if 'android {' in line and not inside_android:
            inside_android = True
            new_lines.append(line)
            
            # Add signing configuration right after android {
            if not signing_added:
                signing_config = '''    // Load signing properties
    def signingPropsFile = rootProject.file('signing.properties')
    
    signingConfigs {
        release {
            if (signingPropsFile.exists()) {
                def props = new Properties()
                props.load(new FileInputStream(signingPropsFile))
                
                storeFile file(props['STORE_FILE'])
                storePassword props['STORE_PASSWORD']
                keyAlias props['KEY_ALIAS']
                keyPassword props['KEY_PASSWORD']
            }
        }
    }

'''
                new_lines.append(signing_config)
                signing_added = True
            i += 1
            continue
        
        # Detect buildTypes block
        if 'buildTypes {' in line and inside_android:
            inside_buildtypes = True
            buildtypes_indent = line[:len(line) - len(line.lstrip())]
            new_lines.append(line)
            i += 1
            continue
        
        # If we're inside buildTypes, look for release block
        if inside_buildtypes and 'release {' in stripped:
            inside_release = True
            release_indent = line[:len(line) - len(line.lstrip())]
            brace_count = 1
            
            # Write release block with signing config
            new_lines.append(f'{release_indent}release {{\n')
            new_lines.append(f'{release_indent}    minifyEnabled false\n')
            new_lines.append(f'{release_indent}    proguardFiles getDefaultProguardFile(\'proguard-android-optimize.txt\'), \'proguard-rules.pro\'\n')
            new_lines.append(f'{release_indent}    signingConfig signingConfigs.release\n')
            
            # Skip the original release block content
            i += 1
            while i < len(lines):
                current_line = lines[i]
                if '{' in current_line:
                    brace_count += 1
                if '}' in current_line:
                    brace_count -= 1
                    if brace_count == 0:
                        new_lines.append(f'{release_indent}}}\n')
                        inside_release = False
                        i += 1
                        break
                i += 1
            continue
        
        # Track when we exit buildTypes
        if inside_buildtypes and stripped == '}':
            inside_buildtypes = False
        
        new_lines.append(line)
        i += 1
    
    # Write modified content back
    with open(build_gradle_path, 'w', encoding='utf-8') as f:
        f.writelines(new_lines)
    
    print("Modified build.gradle to support signing configuration")

def add_dns_support_to_build_gradle(build_gradle_path):
    """Adds OkHttp DNS dependencies to build.gradle."""
    with open(build_gradle_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Add dependencies for DNS over HTTPS
    dns_dependencies = """
    // DNS over HTTPS support
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0'"""
    
    # Find dependencies block and add our dependencies
    if 'dependencies {' in content:
        content = content.replace('dependencies {', f'dependencies {{{dns_dependencies}')
    
    with open(build_gradle_path, 'w', encoding='utf-8') as f:
        f.write(content)
    
    print("Added DNS over HTTPS dependencies to build.gradle")

def add_internet_permission(manifest_path):
    """Ensures INTERNET permission is in AndroidManifest.xml."""
    with open(manifest_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    if 'android.permission.INTERNET' not in content:
        # Add permission before <application> tag
        content = content.replace(
            '<application',
            '    <uses-permission android:name="android.permission.INTERNET" />\n    <application'
        )
        
        with open(manifest_path, 'w', encoding='utf-8') as f:
            f.write(content)
        print("Added INTERNET permission to AndroidManifest.xml")

def create_dns_client_class(package_dir, package_name):
    """Creates CustomDnsClient.java for DNS over HTTPS."""
    dns_client_content = f"""package {package_name};

import android.content.Context;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.HttpUrl;
import okhttp3.dnsoverhttps.DnsOverHttps;
import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

public class CustomDnsClient {{
    
    private static CustomDnsClient instance;
    private OkHttpClient client;
    
    private CustomDnsClient(Context context) {{
        try {{
            // Создаем кэш для DNS запросов
            File cacheDir = new File(context.getCacheDir(), "dns_cache");
            Cache appCache = new Cache(cacheDir, 10 * 1024 * 1024);
            
            // Создаем bootstrap клиент для начальных DNS запросов
            OkHttpClient bootstrapClient = new OkHttpClient.Builder()
                .cache(appCache)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
            
            // Настраиваем DNS over HTTPS для dns.malw.link
            DnsOverHttps dns = new DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(HttpUrl.parse("https://dns.malw.link/dns-query"))
                .bootstrapDnsHosts(
                    InetAddress.getByName("84.21.189.133"),  // IPv4 DNS 1
                    InetAddress.getByName("64.188.98.242")   // IPv4 DNS 2
                )
                .build();
            
            // Создаем основной клиент с кастомным DNS
            client = bootstrapClient.newBuilder()
                .dns(dns)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
                
        }} catch (UnknownHostException e) {{
            e.printStackTrace();
            // Fallback to default client
            client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        }}
    }}
    
    public static synchronized CustomDnsClient getInstance(Context context) {{
        if (instance == null) {{
            instance = new CustomDnsClient(context.getApplicationContext());
        }}
        return instance;
    }}
    
    public OkHttpClient getClient() {{
        return client;
    }}
}}
"""
    
    dns_client_path = os.path.join(package_dir, 'CustomDnsClient.java')
    with open(dns_client_path, 'w', encoding='utf-8') as f:
        f.write(dns_client_content)
    
    print("Created CustomDnsClient.java for DNS over HTTPS")

def modify_mainactivity_for_dns(main_activity_path, package_name, url):
    """Modifies MainActivity to use CustomDnsClient with WebView interception."""
    
    # Create new MainActivity with DNS support via WebViewClient interception
    new_main_activity = f"""package {package_name};

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Nullable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {{

    private WebView webView;
    private OkHttpClient dnsClient;
    private static final String DEFAULT_URL = "{url}";
    private static final String TAG = "DNSWebView";

    @Override
    protected void onCreate(Bundle savedInstanceState) {{
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Инициализируем DNS клиент
        dnsClient = CustomDnsClient.getInstance(this).getClient();

        webView = findViewById(R.id.webview);
        
        // Устанавливаем кастомный WebViewClient для перехвата запросов
        webView.setWebViewClient(new CustomWebViewClient());

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        // Загружаем URL
        webView.loadUrl(DEFAULT_URL);
        Log.d(TAG, "Loading URL with DNS over HTTPS: " + DEFAULT_URL);
    }}

    private class CustomWebViewClient extends WebViewClient {{
        
        @Override
        @Nullable
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {{
            String requestUrl = request.getUrl().toString();
            
            // Пропускаем data: и blob: URL
            if (requestUrl.startsWith("data:") || requestUrl.startsWith("blob:")) {{
                return super.shouldInterceptRequest(view, request);
            }}
            
            try {{
                // Выполняем запрос через OkHttp с кастомным DNS
                Request.Builder requestBuilder = new Request.Builder()
                    .url(requestUrl);
                
                // Копируем заголовки из оригинального запроса
                Map<String, String> headers = request.getRequestHeaders();
                for (Map.Entry<String, String> entry : headers.entrySet()) {{
                    requestBuilder.addHeader(entry.getKey(), entry.getValue());
                }}
                
                Response response = dnsClient.newCall(requestBuilder.build()).execute();
                
                if (response.isSuccessful() && response.body() != null) {{
                    String contentType = response.header("Content-Type", "text/html");
                    String encoding = "UTF-8";
                    
                    // Извлекаем encoding из Content-Type
                    if (contentType != null && contentType.contains("charset=")) {{
                        String[] parts = contentType.split("charset=");
                        if (parts.length > 1) {{
                            encoding = parts[1].split(";")[0].trim();
                        }}
                        // Убираем charset из contentType для WebResourceResponse
                        contentType = contentType.split(";")[0].trim();
                    }}
                    
                    // Получаем все заголовки ответа
                    Map<String, String> responseHeaders = new HashMap<>();
                    for (String name : response.headers().names()) {{
                        responseHeaders.put(name, response.header(name));
                    }}
                    
                    InputStream inputStream = response.body().byteStream();
                    
                    Log.d(TAG, "DNS request successful: " + requestUrl);
                    
                    return new WebResourceResponse(
                        contentType,
                        encoding,
                        response.code(),
                        response.message(),
                        responseHeaders,
                        inputStream
                    );
                }} else {{
                    Log.e(TAG, "DNS request failed: " + requestUrl + " Code: " + response.code());
                }}
                
            }} catch (IOException e) {{
                Log.e(TAG, "Error loading URL with DNS: " + requestUrl, e);
            }}
            
            // В случае ошибки используем стандартную загрузку
            return super.shouldInterceptRequest(view, request);
        }}
    }}

    @Override
    public void onBackPressed() {{
        if (webView.canGoBack()) {{
            webView.goBack();
        }} else {{
            super.onBackPressed();
        }}
    }}
}}
"""
    
    with open(main_activity_path, 'w', encoding='utf-8') as f:
        f.write(new_main_activity)
    
    print("Modified MainActivity.java to use DNS over HTTPS with WebView interception")

def create_project_from_template(template_dir, new_project_dir, app_name, package_name, url, icon_path=None, icon_bg_color=None, signing_config=None, enable_dns=False):
    """Copies the template and modifies it."""
    if os.path.exists(new_project_dir):
        shutil.rmtree(new_project_dir)
    shutil.copytree(template_dir, new_project_dir)

    # --- DEBUGGING --- 
    print("--- DEBUG: Listing contents of new project directory ---")
    try:
        print(os.listdir(new_project_dir))
        print(os.listdir(os.path.join(new_project_dir, 'app')))
    except Exception as e:
        print(f"DEBUG: Error listing directories: {e}")
    print("--- END DEBUG ---")

    print(f"--- Modifying project for {app_name} ---")

    # 1. Change strings.xml
    strings_xml_path = os.path.join(new_project_dir, 'app', 'src', 'main', 'res', 'values', 'strings.xml')
    with open(strings_xml_path, 'r', encoding='utf-8') as f:
        content = f.read()
    content = content.replace('__APP_NAME__', app_name)
    content = content.replace('__URL__', url)
    with open(strings_xml_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Updated strings.xml")

    # 2. Change build.gradle
    build_gradle_path = new_project_dir + os.sep + 'app' + os.sep + 'build.gradle'
    print(f"--- DEBUG: Attempting to open: {build_gradle_path} ---")
    with open(build_gradle_path, 'r', encoding='utf-8') as f:
        content = f.read()
    content = content.replace('com.example.template', package_name)
    with open(build_gradle_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Updated app/build.gradle")

    # 3. Create correct package directory structure
    old_package_path = os.path.join(new_project_dir, 'app', 'src', 'main', 'java', 'com', 'example', 'template')
    new_package_dir = os.path.join(new_project_dir, 'app', 'src', 'main', 'java', *package_name.split('.'))
    os.makedirs(new_package_dir, exist_ok=True)
    
    # Move MainActivity
    old_main_activity_path = os.path.join(old_package_path, 'MainActivity.java')
    new_main_activity_path = os.path.join(new_package_dir, 'MainActivity.java')
    shutil.move(old_main_activity_path, new_main_activity_path)

    # Update MainActivity package
    with open(new_main_activity_path, 'r', encoding='utf-8') as f:
        content = f.read()
    content = content.replace('package com.example.template;', f'package {package_name};')
    with open(new_main_activity_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Updated and moved MainActivity.java")

    # Clean up old directories
    shutil.rmtree(os.path.join(new_project_dir, 'app', 'src', 'main', 'java', 'com'))

    # 4. Add DNS support if enabled
    if enable_dns:
        print("\n--- Добавление поддержки DNS over HTTPS ---")
        add_dns_support_to_build_gradle(build_gradle_path)
        create_dns_client_class(new_package_dir, package_name)
        modify_mainactivity_for_dns(new_main_activity_path, package_name, url)
        
        # Ensure INTERNET permission
        manifest_path = os.path.join(new_project_dir, 'app', 'src', 'main', 'AndroidManifest.xml')
        add_internet_permission(manifest_path)
        
        print("--- DNS over HTTPS успешно встроен (dns.malw.link) ---")
        print("--- Все запросы WebView будут идти через кастомный DNS ---")

    # 5. Generate icons if path is provided
    if icon_path:
        project_res_dir = os.path.join(new_project_dir, 'app', 'src', 'main', 'res')
        generate_icons(icon_path, project_res_dir, icon_bg_color)
    
    # 6. Create gradle.properties to allow non-ASCII paths
    gradle_props_path = os.path.join(new_project_dir, 'gradle.properties')
    with open(gradle_props_path, 'w', encoding='utf-8') as f:
        f.write('android.overridePathCheck=true\n')
        f.write('android.useAndroidX=true\n')
        f.write('android.enableJetifier=true\n')
    print("Created gradle.properties to allow non-ASCII characters.")

    # 7. Setup signing configuration if provided
    if signing_config:
        create_signing_config(
            new_project_dir,
            signing_config['keystore_path'],
            signing_config['keystore_password'],
            signing_config['key_alias'],
            signing_config['key_password']
        )
        modify_build_gradle_for_signing(build_gradle_path)

    print("--- Project modification complete ---")


def build_apk(project_dir, sdk_path, build_type='debug'):
    """Runs the gradle build command."""
    print(f"--- Starting APK build ({build_type}) in {project_dir} ---")
    
    # Set local.properties to point to the SDK
    local_properties_path = os.path.join(project_dir, 'local.properties')
    with open(local_properties_path, 'w') as f:
        # Use forward slashes, which is safer for Gradle
        f.write(f'sdk.dir={sdk_path.replace("\\", "/")}')
    print(f"Created local.properties with SDK path: {sdk_path}")

    gradlew_path = os.path.join(project_dir, 'gradlew.bat')
    
    # Choose the appropriate gradle task
    gradle_task = 'assembleRelease' if build_type == 'release' else 'assembleDebug'
    
    # On Windows, we need to use 'cmd /c' to run the .bat file
    command = [gradlew_path, gradle_task]
    
    process = subprocess.Popen(command, cwd=project_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')

    while True:
        line = process.stdout.readline()
        if not line:
            break
        print(line.strip())

    process.wait()

    if process.returncode == 0:
        print("--- Build successful! ---")
        apk_filename = f'app-{build_type}.apk'
        apk_path = os.path.join(project_dir, 'app', 'build', 'outputs', 'apk', build_type, apk_filename)
        final_apk_name = f"{os.path.basename(project_dir)}-{build_type}.apk"
        final_apk_path = os.path.join(os.path.dirname(project_dir), final_apk_name)
        shutil.copy(apk_path, final_apk_path)
        print(f"APK copied to: {final_apk_path}")
    else:
        print(f"!! Build failed with exit code {process.returncode} !!")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Generate a WebView APK from a URL.')
    parser.add_argument('url', nargs='?', default=None, help='The URL to open in the WebView.')
    parser.add_argument('--app_name', default=None, help='The name of the application.')
    parser.add_argument('--package_name', default=None, help='The package name (e.g., com.example.myapp).')
    parser.add_argument('--icon_path', default=None, help='Path to the custom icon file (png, jpg).')
    parser.add_argument('--icon_bg_color', default='#FFFFFF', help='Background color for the icon in hex format (e.g., #FFFFFF).')
    parser.add_argument('--template_dir', default='WebViewAppTemplate', help='Path to the template project.')
    parser.add_argument('--enable_dns', action='store_true', help='Enable DNS over HTTPS (dns.malw.link).')
    
    # Signing parameters
    parser.add_argument('--keystore_path', default=None, help='Path to the keystore file (.jks or .keystore).')
    parser.add_argument('--keystore_password', default=None, help='Password for the keystore.')
    parser.add_argument('--key_alias', default=None, help='Alias of the key in the keystore.')
    parser.add_argument('--key_password', default=None, help='Password for the key alias.')
    
    args = parser.parse_args()

    url = args.url
    app_name = args.app_name
    package_name = args.package_name
    icon_path = args.icon_path
    icon_bg_color = args.icon_bg_color
    enable_dns = args.enable_dns

    if not url:
        url = input("Введите URL сайта: ")
    
    if not app_name:
        app_name = input("Введите название для приложения: ")

    if not package_name:
        while True:
            package_name = input("Введите имя пакета (например, com.mycompany.myapp): ")
            if '.' in package_name and len(package_name.split('.')) >= 2 and '' not in package_name.split('.'):
                break
            else:
                print("!! Неверный формат. Имя пакета должно быть в формате 'com.company.app', без пробелов.")

    if not icon_path:
        icon_choice = input("Хотите указать путь к своей иконке? (y/n): ").lower()
        if icon_choice == 'y':
            icon_path = input("Введите полный путь к файлу иконки (png или jpg): ")
    
    if icon_path:
        icon_bg_color_input = input(f"Введите цвет фона для иконки в формате HEX [по умолч. #FFFFFF]: ")
        if icon_bg_color_input:
            icon_bg_color = icon_bg_color_input

    # Ask about DNS embedding
    if not enable_dns:
        dns_choice = input("\nВстроить DNS over HTTPS (dns.malw.link) для обхода блокировок? (y/n): ").lower()
        if dns_choice == 'y':
            enable_dns = True
            print("--- DNS over HTTPS будет встроен в приложение ---")

    # Ask about signing - УЛУЧШЕННАЯ ЛОГИКА
    signing_config = None
    keystore_path = args.keystore_path
    
    if not keystore_path:
        sign_choice = input("\nХотите подписать APK своим ключом? (y/n): ").lower()
        if sign_choice == 'y':
            keystore_path = input("Введите путь к keystore файлу (.jks или .keystore): ")
        elif os.path.exists(sign_choice) and (sign_choice.endswith('.jks') or sign_choice.endswith('.keystore')):
            # Пользователь сразу ввел путь вместо y/n
            print(f"\n!! Обнаружен путь к keystore: {sign_choice}")
            keystore_path = sign_choice
    
    if keystore_path and os.path.exists(keystore_path):
        keystore_password = args.keystore_password or input("Введите пароль keystore: ")
        key_alias = args.key_alias or input("Введите alias ключа: ")
        key_password = args.key_password or input("Введите пароль ключа: ")
        
        signing_config = {
            'keystore_path': keystore_path,
            'keystore_password': keystore_password,
            'key_alias': key_alias,
            'key_password': key_password
        }
        build_type = 'release'
        print("\n--- Будет создан подписанный Release APK ---")
    else:
        build_type = 'debug'
        if keystore_path:
            print(f"!! Keystore файл не найден: {keystore_path}")
        print("!! Будет создан неподписанный Debug APK")

    print("\n--- Начинаем создание APK с параметрами: ---")
    print(f"URL: {url}")
    print(f"Название: {app_name}")
    print(f"Пакет: {package_name}")
    if icon_path:
        print(f"Иконка: {icon_path}")
        print(f"Фон иконки: {icon_bg_color}")
    if enable_dns:
        print(f"DNS: ВКЛЮЧЕН (dns.malw.link)")
        print(f"     ВСЕ запросы WebView будут проходить через DoH!")
    if signing_config:
        print(f"Keystore: {keystore_path}")
        print(f"Key Alias: {key_alias}")
    print(f"Тип сборки: {build_type.UPPER()}")
    print("\n")

    new_project_name = app_name.replace(' ', '')
    new_project_dir = os.path.join(os.path.dirname(__file__), new_project_name)

    sdk_path = get_sdk_path()

    if sdk_path:
        create_project_from_template(args.template_dir, new_project_dir, app_name, package_name, url, icon_path, icon_bg_color, signing_config, enable_dns)
        build_apk(new_project_dir, sdk_path, build_type)
