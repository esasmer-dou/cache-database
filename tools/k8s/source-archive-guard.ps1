function Get-K8sSourceArchiveFiles {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)][string]$Root)

    $roster = @(git -C $Root -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot establish Git source roster before source transfer.' }
    $files = @($roster | Where-Object { [IO.File]::Exists([IO.Path]::Combine($Root, $_)) })
    $firstArchiveSecond = [DateTime]::new(1970, 1, 1, 0, 0, 1, [DateTimeKind]::Utc)
    foreach ($file in $files) {
        $modified = [IO.File]::GetLastWriteTimeUtc([IO.Path]::Combine($Root, $file))
        # Archive seconds truncate fractional timestamps; epoch 0.x is still unsafe.
        if ($modified -lt $firstArchiveSecond) {
            throw "Source timestamp must be at least 1970-01-01T00:00:01Z: $file. Source files were not rewritten."
        }
    }
    return $files
}
