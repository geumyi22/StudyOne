using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows;

namespace StudyOne.Desktop;

public partial class App : Application
{
    private Mutex? instance;
    private bool ownsInstance;
    protected override void OnStartup(StartupEventArgs e)
    {
        // Acquire BEFORE constructing stores/windows. Separate Windows sessions or
        // noncooperating file editors are not protected by this session-local mutex.
        try
        {
            string identity = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
                StudyData.DataDirectory.ToUpperInvariant())));
            instance = new Mutex(true, @"Local\StudyOneCanary-" + identity, out ownsInstance);
            if (!ownsInstance)
            {
                MessageBox.Show("StudyOne이 이미 실행 중입니다. 기존 창을 사용해 주세요.\n학습 기록은 변경하지 않았습니다.", "StudyOne");
                Shutdown(); return;
            }
            base.OnStartup(e);
            var window = new MainWindow();
            window.EnableLearningTools();
            MainWindow = window;
            window.Show();
        }
        catch (Exception error)
        {
            MessageBox.Show("안전하게 앱을 시작하지 못했습니다.\n" + error.Message, "StudyOne");
            Shutdown(1);
        }
    }
    protected override void OnExit(ExitEventArgs e)
    {
        if (ownsInstance) { instance?.ReleaseMutex(); ownsInstance = false; }
        instance?.Dispose(); instance = null;
        base.OnExit(e);
    }
}
