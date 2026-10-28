# Shows the Windows Common Item Dialog (the same open/save/folder dialog Explorer uses) and prints the chosen
# paths, one per line, each as base64 of UTF-8 so no console code page can mangle them.
# Input comes from the MC_* environment variables. Exit code: 0 picked, 1 cancelled, 2 failed.
$ErrorActionPreference = 'Stop'

$base = @'
[PreserveSig] int Show(IntPtr parent);
void SetFileTypes(uint cFileTypes, [MarshalAs(UnmanagedType.LPArray)] COMDLG_FILTERSPEC[] rgFilterSpec);
void SetFileTypeIndex(uint iFileType);
void GetFileTypeIndex(out uint piFileType);
void Advise(IntPtr pfde, out uint pdwCookie);
void Unadvise(uint dwCookie);
void SetOptions(uint fos);
void GetOptions(out uint pfos);
void SetDefaultFolder(IShellItem psi);
void SetFolder(IShellItem psi);
void GetFolder(out IShellItem ppsi);
void GetCurrentSelection(out IShellItem ppsi);
void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
void GetResult(out IShellItem ppsi);
void AddPlace(IShellItem psi, int fdap);
void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
void Close(int hr);
void SetClientGuid(ref Guid guid);
void ClearClientData();
void SetFilter(IntPtr pFilter);
'@

$source = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class McPicker
{
    [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")] private class FileOpenDialogClass { }
    [ComImport, Guid("C0B4E2F3-BA21-4773-8DBA-335EC946EB8B")] private class FileSaveDialogClass { }

    [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileDialog
    {
        /*BASE*/
    }

    [ComImport, Guid("d57c7288-d4ad-4768-be02-9d969532d960"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileOpenDialog
    {
        /*BASE*/
        void GetResults(out IShellItemArray ppenum);
        void GetSelectedItems(out IShellItemArray ppsai);
    }

    [ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItem
    {
        void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
        void GetParent(out IShellItem ppsi);
        void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
        void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
        void Compare(IShellItem psi, uint hint, out int piOrder);
    }

    [ComImport, Guid("b63ea76d-1f85-456f-a19c-48159efa858b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemArray
    {
        void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid rbhid, out IntPtr ppvOut);
        void GetPropertyStore(int flags, ref Guid riid, out IntPtr ppv);
        void GetPropertyDescriptionList(IntPtr keyType, ref Guid riid, out IntPtr ppv);
        void GetAttributes(int attribFlags, uint sfgaoMask, out uint psfgaoAttribs);
        void GetCount(out uint pdwNumItems);
        void GetItemAt(uint dwIndex, out IShellItem ppsi);
        void EnumItems(out IntPtr ppenumShellItems);
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct COMDLG_FILTERSPEC
    {
        [MarshalAs(UnmanagedType.LPWStr)] public string pszName;
        [MarshalAs(UnmanagedType.LPWStr)] public string pszSpec;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    private static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, ref Guid riid, out IShellItem item);

    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    private const uint FOS_OVERWRITEPROMPT = 0x2;
    private const uint FOS_NOCHANGEDIR = 0x8;
    private const uint FOS_PICKFOLDERS = 0x20;
    private const uint FOS_FORCEFILESYSTEM = 0x40;
    private const uint FOS_ALLOWMULTISELECT = 0x200;
    private const uint FOS_PATHMUSTEXIST = 0x800;
    private const uint FOS_FILEMUSTEXIST = 0x1000;
    private const uint FOS_FORCESHOWHIDDEN = 0x10000000;
    private const uint SIGDN_FILESYSPATH = 0x80058000;
    private const int HRESULT_CANCELLED = unchecked((int)0x800704C7);

    private static string PathOf(IShellItem item)
    {
        string path;
        item.GetDisplayName(SIGDN_FILESYSPATH, out path);
        return path;
    }

    // The dialog belongs to the app's window when that is the one in front, so it opens on top of it.
    private static IntPtr OwnerOf(uint appPid)
    {
        IntPtr hwnd = GetForegroundWindow();
        uint pid;
        GetWindowThreadProcessId(hwnd, out pid);
        return pid == appPid ? hwnd : IntPtr.Zero;
    }

    // Returns the chosen paths, or null when the dialog was cancelled.
    public static string[] Pick(string mode, bool multiple, string title, string start, string name,
        string filterName, string filterSpec, string extension, uint appPid)
    {
        bool save = mode == "save";
        bool folder = mode == "folder";
        object com = save ? (object)new FileSaveDialogClass() : (object)new FileOpenDialogClass();
        IFileDialog dialog = (IFileDialog)com;

        uint options;
        dialog.GetOptions(out options);
        options |= FOS_FORCEFILESYSTEM | FOS_FORCESHOWHIDDEN | FOS_NOCHANGEDIR | FOS_PATHMUSTEXIST;
        if (folder) options |= FOS_PICKFOLDERS;
        if (!save && !folder) options |= FOS_FILEMUSTEXIST;
        if (save) options |= FOS_OVERWRITEPROMPT;
        if (multiple && !save) options |= FOS_ALLOWMULTISELECT;
        dialog.SetOptions(options);

        if (!string.IsNullOrEmpty(title)) dialog.SetTitle(title);
        if (!folder && !string.IsNullOrEmpty(filterSpec))
        {
            COMDLG_FILTERSPEC[] types = new COMDLG_FILTERSPEC[1];
            types[0].pszName = string.IsNullOrEmpty(filterName) ? filterSpec : filterName;
            types[0].pszSpec = filterSpec;
            dialog.SetFileTypes(1, types);
            dialog.SetFileTypeIndex(1);
        }
        if (save && !string.IsNullOrEmpty(extension)) dialog.SetDefaultExtension(extension);
        if (!string.IsNullOrEmpty(name)) dialog.SetFileName(name);
        if (!string.IsNullOrEmpty(start) && System.IO.Directory.Exists(start))
        {
            Guid shellItemId = new Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe");
            IShellItem folderItem;
            SHCreateItemFromParsingName(start, IntPtr.Zero, ref shellItemId, out folderItem);
            dialog.SetFolder(folderItem);
        }

        int hr = dialog.Show(OwnerOf(appPid));
        if (hr == HRESULT_CANCELLED) return null;
        if (hr != 0) Marshal.ThrowExceptionForHR(hr);

        List<string> paths = new List<string>();
        if (multiple && !save)
        {
            IShellItemArray items;
            ((IFileOpenDialog)com).GetResults(out items);
            uint count;
            items.GetCount(out count);
            for (uint i = 0; i < count; i++)
            {
                IShellItem item;
                items.GetItemAt(i, out item);
                paths.Add(PathOf(item));
            }
        }
        else
        {
            IShellItem item;
            dialog.GetResult(out item);
            paths.Add(PathOf(item));
        }
        return paths.ToArray();
    }
}
'@

try {
    Add-Type -TypeDefinition $source.Replace('/*BASE*/', $base)
    $paths = [McPicker]::Pick(
        $env:MC_MODE, ($env:MC_MULTIPLE -eq '1'), $env:MC_TITLE, $env:MC_START, $env:MC_NAME,
        $env:MC_FILTER_NAME, $env:MC_FILTER_SPEC, $env:MC_EXTENSION, [uint32]$env:MC_PID)
    if ($null -eq $paths) { exit 1 }
    foreach ($p in $paths) {
        [Console]::Out.WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($p)))
    }
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.ToString())
    exit 2
}
