#requires -Version 7.2
param([string]$Checker = (Join-Path $PSScriptRoot 'check-public-api-compatibility.ps1'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Resolve-Path -LiteralPath $Checker).Path, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Public API checker has parse errors.' }
foreach ($name in @('Get-ApiBlocks', 'Get-MissingApiMembers')) {
    $definitions = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true))
    if ($definitions.Count -ne 1) { throw "Missing/duplicate checker function: $name" }
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}
$header = '## cachedb-storage-redis :: com.reactor.cachedb.redis.RedisEntityRepository'
$declaration = 'public final class com.reactor.cachedb.redis.RedisEntityRepository<T, ID> implements com.reactor.cachedb.core.api.EntityRepository<T, ID>, com.reactor.cachedb.core.change.ExternalChangeHydrationRepository<T, ID>'
$member = '  public java.util.Optional<T> findById(ID);'
$before = "$header`n$declaration {`n$member`n}`n"
$after = $before.Replace('HydrationRepository<T, ID> {', 'HydrationRepository<T, ID>, com.reactor.cachedb.core.api.RedisLexicalWindowRepository<T> {')
function Assert-Compatible([string]$Name, [string]$Candidate, [bool]$Compatible) {
    $missing = @(Get-MissingApiMembers $before $Candidate)
    if (($missing.Count -eq 0) -ne $Compatible) { throw "Wrong compatibility result: $Name" }
    Write-Host "PASS $Name"
}
Assert-Compatible 'identical' $before $true
Assert-Compatible 'additive-lexical-interface' $after $true
Assert-Compatible 'final-removal' ($after.Replace('public final class ', 'public class ')) $true
Assert-Compatible 'additive-method' ($after.Replace($member, "$member`n  public long newMethod();")) $true
Assert-Compatible 'removed-original-entity-interface' ($after.Replace('com.reactor.cachedb.core.api.EntityRepository<T, ID>, ', '')) $false
Assert-Compatible 'removed-original-hydration-interface' ($after.Replace(', com.reactor.cachedb.core.change.ExternalChangeHydrationRepository<T, ID>', '')) $false
Assert-Compatible 'changed-original-interface-type' ($after.Replace('EntityRepository<T, ID>', 'EntityRepository<T, java.lang.String>')) $false
Assert-Compatible 'removed-method' ($after.Replace($member, '')) $false
Assert-Compatible 'changed-return-type' ($after.Replace('java.util.Optional<T>', 'java.util.List<T>')) $false
Assert-Compatible 'changed-parameter' ($after.Replace('findById(ID)', 'findById(java.lang.String)')) $false
Assert-Compatible 'added-abstract-class' ($after.Replace('public final class ', 'public abstract class ')) $false
Assert-Compatible 'removed-class' '' $false
$foreign = $after.Replace('com.reactor.cachedb.redis.RedisEntityRepository', 'com.example.OtherRepository')
if (@(Get-MissingApiMembers ($before.Replace('com.reactor.cachedb.redis.RedisEntityRepository', 'com.example.OtherRepository')) $foreign).Count -eq 0) {
    throw 'Lexical-interface exception leaked to an unrelated class.'
}
Write-Host 'PASS unrelated-class-not-normalized'
Write-Host 'Public API comparison controls passed: 13.'
