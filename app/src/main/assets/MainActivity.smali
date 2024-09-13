.class public Lcom/kaanelloed/iconerationiconpack/MainActivity;
.super Landroid/app/Activity;
.source "MainActivity.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 5
    invoke-direct {p0}, Landroid/app/Activity;-><init>()V

    return-void
.end method


# virtual methods
.method protected onCreate(Landroid/os/Bundle;)V
    .locals 1
    .param p1, "savedInstanceState"    # Landroid/os/Bundle;

    .line 9
    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    .line 10
    const/high16 v0, 0x7f010000    # @layout/main_activity 'res/layout/main_activity.xml'

    invoke-virtual {p0, v0}, Lcom/kaanelloed/iconerationiconpack/MainActivity;->setContentView(I)V

    .line 11
    return-void
.end method
