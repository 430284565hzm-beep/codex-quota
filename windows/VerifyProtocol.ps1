$ErrorActionPreference='Stop'
Add-Type -AssemblyName PresentationFramework,PresentationCore,WindowsBase,System.Xaml,System.Web.Extensions,System.Drawing
Add-Type -Path (Join-Path $PSScriptRoot 'UsageClient.cs') -ReferencedAssemblies System.Web.Extensions,System.Drawing,PresentationCore,PresentationFramework,WindowsBase,System.Xaml
$fixturePath=Join-Path $PSScriptRoot ('mock-server-'+[Guid]::NewGuid().ToString('N')+'.exe')
$source=@'
using System;
using System.Collections.Generic;
using System.Web.Script.Serialization;
public static class FixtureServer {
    public static void Main() {
        var serializer=new JavaScriptSerializer();
        var redeemed=new HashSet<string>();
        int count=2,used=98;
        string line;
        while((line=Console.ReadLine())!=null) {
            var request=serializer.Deserialize<Dictionary<string,object>>(line);
            if(!request.ContainsKey("id"))continue;
            string method=Convert.ToString(request["method"]);
            object result=null,error=null;
            if(method=="initialize")result=new { userAgent="fixture" };
            else if(method=="account/rateLimits/read")result=new {
                rateLimits=new { primary=new { usedPercent=used,windowDurationMins=300 } },
                rateLimitResetCredits=new { availableCount=count }
            };
            else if(method=="account/rateLimitResetCredit/consume") {
                var p=request["params"] as Dictionary<string,object>;
                string key=p!=null && p.ContainsKey("idempotencyKey") ? Convert.ToString(p["idempotencyKey"]) : null;
                string credit=p!=null && p.ContainsKey("creditId") ? Convert.ToString(p["creditId"]) : null;
                if(String.IsNullOrWhiteSpace(key) || credit!="opaque_fixture_credit")error=new { code=-32602,message="Invalid key or credit ID" };
                else {
                    string outcome;
                    if(redeemed.Contains(key))outcome="alreadyRedeemed";
                    else if(count==0)outcome="noCredit";
                    else if(used==0)outcome="nothingToReset";
                    else {count--;used=0;redeemed.Add(key);outcome="reset";}
                    result=new { outcome=outcome };
                }
            } else error=new {code=-32601,message="Unknown method"};
            if(error==null)Console.WriteLine(serializer.Serialize(new {id=request["id"],result=result}));
            else Console.WriteLine(serializer.Serialize(new {id=request["id"],error=error}));
        }
    }
}
'@
Add-Type -TypeDefinition $source -OutputAssembly $fixturePath -OutputType ConsoleApplication -ReferencedAssemblies System.Web.Extensions
$client=[UsageClient]::new($fixturePath)
try {
    $start=$client.FetchAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if($start.rateLimitResetCredits.availableCount -ne 2){throw 'Read protocol failed'}
    $key=[Guid]::NewGuid().ToString()
    $first=$client.ConsumeResetAsync($key,'opaque_fixture_credit').GetAwaiter().GetResult() | ConvertFrom-Json
    $retry=$client.ConsumeResetAsync($key,'opaque_fixture_credit').GetAwaiter().GetResult() | ConvertFrom-Json
    $limits=$client.FetchAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if($first.outcome -ne 'reset' -or $retry.outcome -ne 'alreadyRedeemed' -or $limits.rateLimitResetCredits.availableCount -ne 1){throw 'Wire idempotency or balance refresh failed'}
    $nothing=$client.ConsumeResetAsync([Guid]::NewGuid().ToString(),'opaque_fixture_credit').GetAwaiter().GetResult() | ConvertFrom-Json
    if($nothing.outcome -ne 'nothingToReset'){throw 'Server outcome was not preserved'}
    $invalid=$false
    try{[void]$client.ConsumeResetAsync([Guid]::NewGuid().ToString(),'wrong_credit').GetAwaiter().GetResult()}catch{ $invalid=$_.Exception.GetBaseException().Code -eq -32602 }
    if(!$invalid){throw 'Protocol error code was discarded'}
    'PASS: real UsageClient transport against local fixture; correct methods/parameters, credit ID, idempotent retry consumes once, server balance refresh, error propagation; no account redemption'
}finally{$client.Dispose();Remove-Item -LiteralPath $fixturePath -Force -ErrorAction SilentlyContinue}
